package com.salonreview.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.TextBlockParam;
import com.salonreview.config.AiFunnelAnalysisProperties;
import com.salonreview.domain.FunnelAnalysis;
import com.salonreview.marketing.FunnelAnalyticsService;
import com.salonreview.repo.FunnelAnalysisRepository;
import com.salonreview.web.dto.FunnelDashboardDto;
import com.salonreview.web.dto.FunnelDashboardDto.FunnelStepStat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

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

    private static final long MAX_OUTPUT_TOKENS = 1536L;

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
     * Analyze one landing page's funnel for a specific flow_key (a page normally has exactly
     * one). Recomputes the funnel itself (rather than trusting client-supplied numbers) so the
     * analysis is always grounded in the same data the dashboard just showed. Returns
     * {@link Optional#empty()} when the feature is off, or when the slug/flowKey combination has
     * no funnel data (→ 404 in the controller).
     */
    @Transactional
    public Optional<FunnelAnalysisResult> analyze(String slug, String flowKey) {
        if (!props.isEnabled()) return Optional.empty();
        AnthropicClient client = anthropicClientProvider.getIfAvailable();
        if (client == null) return Optional.empty();

        List<FunnelDashboardDto> funnels = funnelAnalyticsService.funnel(slug);
        FunnelDashboardDto funnel = funnels.stream()
                .filter(f -> f.flowKey().equals(flowKey))
                .findFirst()
                .orElse(null);
        if (funnel == null) return Optional.empty();

        String fingerprint = fingerprint(funnel);

        Optional<FunnelAnalysis> cached = analyses
                .findFirstByLandingPageSlugAndFlowKeyAndPromptVersionAndSnapshotFingerprintOrderByCreatedAtDesc(
                        slug, flowKey, FunnelAnalysisPrompts.PROMPT_VERSION, fingerprint);
        if (cached.isPresent()) {
            return Optional.of(FunnelAnalysisResult.fromEntity(cached.get()));
        }

        String userMessage = buildUserMessage(slug, funnel);
        FunnelAnalysisResult result;
        try {
            result = callClaude(client, userMessage);
        } catch (RefusalException re) {
            log.warn("Funnel analysis refused for slug={} flowKey={}: {}", slug, flowKey, re.category());
            result = refusalFallback(re.category());
        } catch (Exception e) {
            log.error("Claude funnel analysis failed for slug={} flowKey={}: {}", slug, flowKey, e.toString());
            throw new AnalysisFailedException("LLM call failed", e);
        }

        FunnelAnalysis saved = persist(slug, flowKey, fingerprint, result);
        return Optional.of(FunnelAnalysisResult.fromEntity(saved));
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
     * {@link SuspiciousBookingTriageService#callClaude}. Package-private so tests can override. */
    FunnelAnalysisResult callClaude(AnthropicClient client, String userMessage) throws RefusalException {
        StructuredMessageCreateParams<FunnelAnalysisResult> params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(MAX_OUTPUT_TOKENS)
                .systemOfTextBlockParams(List.of(
                        TextBlockParam.builder()
                                .text(FunnelAnalysisPrompts.SYSTEM_PROMPT_V1)
                                .cacheControl(CacheControlEphemeral.builder().build())
                                .build()))
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
        return new FunnelAnalysisResult(
                parsed.biggestBottleneckStep(),
                parsed.bottleneckExplanation(),
                parsed.recommendations(),
                parsed.suspiciousPatterns(),
                parsed.suggestedAbTests(),
                parsed.topPriorityAction(),
                FunnelAnalysisPrompts.PROMPT_VERSION,
                MODEL);
    }

    private FunnelAnalysisResult refusalFallback(String category) {
        return new FunnelAnalysisResult(
                "unknown",
                "This funnel couldn't be analyzed automatically (" + category + "). Please review the numbers manually.",
                List.of(),
                List.of(),
                List.of(),
                "Review the funnel numbers manually.",
                FunnelAnalysisPrompts.PROMPT_VERSION,
                MODEL);
    }

    private FunnelAnalysis persist(String slug, String flowKey, String fingerprint, FunnelAnalysisResult result) {
        FunnelAnalysis row = FunnelAnalysis.builder()
                .landingPageSlug(slug)
                .flowKey(flowKey)
                .promptVersion(FunnelAnalysisPrompts.PROMPT_VERSION)
                .snapshotFingerprint(fingerprint)
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
