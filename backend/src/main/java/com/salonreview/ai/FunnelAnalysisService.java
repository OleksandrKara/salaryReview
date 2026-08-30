package com.salonreview.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.TextBlockParam;
import com.salonreview.config.AiFunnelAnalysisProperties;
import com.salonreview.domain.FunnelAnalysis;
import com.salonreview.domain.Language;
import com.salonreview.marketing.FunnelAnalyticsService;
import com.salonreview.marketing.TrafficSourceParam;
import com.salonreview.repo.FunnelAnalysisRepository;
import com.salonreview.web.dto.FunnelDashboardDto;
import com.salonreview.web.dto.FunnelDashboardDto.FunnelStepStat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Owner-facing AI analysis of a booking funnel ("Analyze Funnel" button) — acts as a CRO
 * consultant rather than a data summarizer (see {@link FunnelAnalysisPrompts}). Mirrors
 * {@link SuspiciousBookingTriageService}'s shape exactly: feature-flagged bean, cache lookup
 * before calling Claude, structured outputs, owner-driven and on-demand only (never a batch job).
 */
@Service
public class FunnelAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(FunnelAnalysisService.class);

    /** Sonnet, not Haiku (unlike triage) — this is a low-frequency, owner-triggered "consultant"
     * task where reasoning quality matters more than the marginal cost/latency difference. */
    static final String MODEL = "claude-sonnet-5";

    // Was 1536 — too tight for the full schema (bottleneck explanation + up to 5 recommendations
    // + suspicious patterns + A/B tests + top priority action), so Claude's JSON was getting cut
    // off mid-string on funnels with enough content to say. 4096 gives real headroom; this is a
    // low-frequency, owner-triggered call, so the extra token ceiling costs nothing in practice.
    private static final long MAX_OUTPUT_TOKENS = 4096L;

    private final ObjectProvider<AnthropicClient> anthropicClientProvider;
    private final AiFunnelAnalysisProperties props;
    private final FunnelAnalysisRepository analyses;
    private final FunnelAnalyticsService funnelAnalyticsService;

    public FunnelAnalysisService(ObjectProvider<AnthropicClient> anthropicClientProvider,
                                  AiFunnelAnalysisProperties props,
                                  FunnelAnalysisRepository analyses,
                                  FunnelAnalyticsService funnelAnalyticsService) {
        this.anthropicClientProvider = anthropicClientProvider;
        this.props = props;
        this.analyses = analyses;
        this.funnelAnalyticsService = funnelAnalyticsService;
    }

    /**
     * Analyze one landing page variant's funnel. Recomputes the funnel itself (rather than
     * trusting client-supplied numbers) so the analysis is always grounded in the same data the
     * dashboard just showed — including whichever Ads only/All traffic mode the owner currently
     * has selected. Returns {@link Optional#empty()} when the feature is off, or when the
     * slug/variantId combination has no funnel data (→ 404 in the controller). No separate cache
     * key for adsOnly is needed: the two modes' underlying numbers differ, so the snapshot
     * fingerprint below already disambiguates them.
     *
     * @param force when true, skips the cache lookup and always calls Claude fresh, persisting a
     *              new history row even if the underlying funnel numbers haven't changed since
     *              the last analysis — the owner-facing "run again anyway" action.
     * @param lang  which language to write the analysis in — EN or RU, resolved by the caller from
     *              the requesting owner/ads-manager's preferred-language setting. Part of the
     *              cache lookup, so switching a user's language never serves them a cached result
     *              generated in the other language.
     */
    @Transactional
    public Optional<FunnelAnalysisResult> analyze(String slug, UUID variantId, boolean adsOnly, boolean force, Language lang) {
        if (!props.isEnabled()) return Optional.empty();
        AnthropicClient client = anthropicClientProvider.getIfAvailable();
        if (client == null) return Optional.empty();

        // Always the full history, independent of whatever period filter the owner currently has
        // selected on the dashboard — this analysis is about the funnel's overall health, not a
        // temporary view.
        List<FunnelDashboardDto> funnels = funnelAnalyticsService.funnel(slug, TrafficSourceParam.parse(adsOnly ? null : "all"), null, null);
        FunnelDashboardDto funnel = funnels.stream()
                .filter(f -> f.variantId().equals(variantId))
                .findFirst()
                .orElse(null);
        if (funnel == null) return Optional.empty();

        String fingerprint = fingerprint(funnel);

        if (!force) {
            Optional<FunnelAnalysis> cached = analyses
                    .findFirstByLandingPageSlugAndVariantIdAndPromptVersionAndSnapshotFingerprintAndLanguageOrderByCreatedAtDesc(
                            slug, variantId, FunnelAnalysisPrompts.PROMPT_VERSION, fingerprint, lang);
            if (cached.isPresent()) {
                return Optional.of(FunnelAnalysisResult.fromEntity(cached.get()));
            }
        }

        String userMessage = buildUserMessage(slug, funnel);
        FunnelAnalysisResult result;
        try {
            result = callClaude(client, userMessage, lang);
        } catch (RefusalException re) {
            log.warn("Funnel analysis refused for slug={} variantId={}: {}", slug, variantId, re.category());
            result = refusalFallback(re.category(), lang);
        } catch (Exception e) {
            log.error("Claude funnel analysis failed for slug={} variantId={}: {}", slug, variantId, e.toString());
            throw new AnalysisFailedException("LLM call failed", e);
        }

        FunnelAnalysis saved = persist(slug, variantId, funnel.flowKey(), fingerprint, result, lang);
        return Optional.of(FunnelAnalysisResult.fromEntity(saved));
    }

    /** Past analyses for this landing page variant, newest first, for the owner-facing history
     * list. Empty (not 404) when the feature is disabled or nothing's been analyzed yet — the
     * caller distinguishes "feature off" via {@link AiFunnelAnalysisProperties#isEnabled()}
     * directly. */
    public List<FunnelAnalysisResult> history(String slug, UUID variantId) {
        if (!props.isEnabled()) return List.of();
        return analyses.findTop20ByLandingPageSlugAndVariantIdOrderByCreatedAtDesc(slug, variantId).stream()
                .map(FunnelAnalysisResult::fromEntity)
                .toList();
    }

    /** Deterministic string built from exactly the numbers the LLM sees — an exact match means
     * the analysis would be computed from identical input, so the cached result is still valid.
     * Simpler than deep-comparing the step list as JSON, and self-documenting in a DB row. */
    private String fingerprint(FunnelDashboardDto funnel) {
        StringBuilder sb = new StringBuilder();
        sb.append(funnel.totalVisitors()).append(':')
                .append(funnel.totalStarted()).append(':')
                .append(funnel.totalCompleted());
        for (FunnelStepStat step : funnel.steps()) {
            sb.append('|').append(step.stepKey()).append(',').append(step.reachedCount());
        }
        return sb.toString();
    }

    private String buildUserMessage(String slug, FunnelDashboardDto funnel) {
        StringBuilder sb = new StringBuilder();
        sb.append("Landing page: ").append(slug).append('\n');
        sb.append("Variant: ").append(funnel.variantName());
        if (funnel.variantKey() != null) sb.append(" (").append(funnel.variantKey()).append(')');
        sb.append('\n');
        sb.append("Flow: ").append(funnel.flowKey()).append('\n');
        sb.append("Total visitors (page views): ").append(funnel.totalVisitors()).append('\n');
        sb.append("Started booking: ").append(funnel.totalStarted()).append('\n');
        sb.append("Completed bookings: ").append(funnel.totalCompleted()).append('\n');
        sb.append(String.format(Locale.US, "Final conversion rate (of visitors): %.2f%%%n",
                funnel.finalConversionRate() * 100));
        sb.append('\n').append("Step-by-step funnel:\n");
        for (FunnelStepStat step : funnel.steps()) {
            sb.append(String.format(Locale.US,
                    "- Step %d of %d (%s): %d reached (%.1f%% of started), %d dropped off here (%.1f%% of the previous step)%n",
                    step.stepIndex() + 1, step.stepCountTotal(), step.stepKey(), step.reachedCount(),
                    step.reachedPctOfStarted() * 100, step.dropOffCount(), step.dropOffPct() * 100));
        }
        return sb.toString();
    }

    /** Single Claude call with structured-output enforcement — same mechanism as
     * {@link SuspiciousBookingTriageService#callClaude}. The language directive (if any) rides in
     * its own, uncached system block after the cached base prompt — same technique
     * {@code RagAnswerService} uses — so English requests (the common case) keep a stable cached
     * prefix. Package-private so tests can override. */
    FunnelAnalysisResult callClaude(AnthropicClient client, String userMessage, Language lang) throws RefusalException {
        List<TextBlockParam> system = new ArrayList<>();
        system.add(TextBlockParam.builder()
                .text(FunnelAnalysisPrompts.SYSTEM_PROMPT_V2)
                .cacheControl(CacheControlEphemeral.builder().build())
                .build());
        String directive = FunnelAnalysisPrompts.languageDirective(lang);
        if (directive != null) {
            system.add(TextBlockParam.builder().text(directive).build());
        }

        StructuredMessageCreateParams<FunnelAnalysisResult> params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(MAX_OUTPUT_TOKENS)
                .systemOfTextBlockParams(system)
                .addUserMessage(userMessage)
                .outputConfig(FunnelAnalysisResult.class)
                .build();

        var response = client.messages().create(params);

        String stopReason = response.stopReason() == null ? "" : response.stopReason().toString();
        if (stopReason.contains("refusal")) {
            throw new RefusalException("policy_refusal");
        }

        var firstTyped = response.content().stream()
                .flatMap(cb -> cb.text().stream())
                .findFirst()
                .orElseThrow(() -> new AnalysisFailedException("Claude response had no text block", null));

        FunnelAnalysisResult parsed = firstTyped.text();
        // createdAt is a placeholder here — the caller always re-wraps the persisted entity via
        // fromEntity(saved) before returning to the controller, which fills in the real timestamp.
        return new FunnelAnalysisResult(
                parsed.biggestBottleneckStep(),
                parsed.bottleneckExplanation(),
                parsed.recommendations(),
                parsed.suspiciousPatterns(),
                parsed.suggestedAbTests(),
                parsed.topPriorityAction(),
                FunnelAnalysisPrompts.PROMPT_VERSION,
                MODEL,
                null);
    }

    private FunnelAnalysisResult refusalFallback(String category, Language lang) {
        String explanation = lang == Language.RU
                ? "Не удалось автоматически проанализировать эту воронку (" + category + "). "
                        + "Пожалуйста, просмотрите цифры вручную."
                : "This funnel couldn't be analyzed automatically (" + category + "). Please review the numbers manually.";
        String topPriorityAction = lang == Language.RU
                ? "Просмотрите цифры воронки вручную."
                : "Review the funnel numbers manually.";
        return new FunnelAnalysisResult(
                "unknown",
                explanation,
                List.of(),
                List.of(),
                List.of(),
                topPriorityAction,
                FunnelAnalysisPrompts.PROMPT_VERSION,
                MODEL,
                null);
    }

    private FunnelAnalysis persist(String slug, UUID variantId, String flowKey, String fingerprint, FunnelAnalysisResult result, Language lang) {
        FunnelAnalysis row = FunnelAnalysis.builder()
                .landingPageSlug(slug)
                .variantId(variantId)
                .flowKey(flowKey)
                .promptVersion(FunnelAnalysisPrompts.PROMPT_VERSION)
                .snapshotFingerprint(fingerprint)
                .language(lang)
                .biggestBottleneckStep(result.biggestBottleneckStep())
                .bottleneckExplanation(result.bottleneckExplanation())
                .recommendations(result.recommendations())
                .suspiciousPatterns(result.suspiciousPatterns())
                .suggestedAbTests(result.suggestedAbTests())
                .topPriorityAction(result.topPriorityAction())
                .model(MODEL)
                .build();
        return analyses.save(row);
    }

    /** Translates to 502 in the controller layer. */
    public static class AnalysisFailedException extends RuntimeException {
        public AnalysisFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Internal — the safety classifier refused. Caught and converted to a friendly fallback. */
    static class RefusalException extends Exception {
        private final String category;
        RefusalException(String category) { this.category = category; }
        String category() { return category; }
    }
}
