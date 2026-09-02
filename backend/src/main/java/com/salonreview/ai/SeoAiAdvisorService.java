package com.salonreview.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.TextBlockParam;
import com.salonreview.config.AiSeoAdvisorProperties;
import com.salonreview.domain.Language;
import com.salonreview.domain.SeoAnalysis;
import com.salonreview.repo.SeoAnalysisRepository;
import com.salonreview.seo.SeoAnalysisSnapshot;
import com.salonreview.seo.SeoContextBuilderService;
import com.salonreview.seo.SeoDashboardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Owner-facing AI analysis of the business's SEO position ("Analyze SEO" button) — acts as an SEO
 * consultant rather than a data summarizer (see {@link SeoAdvisorPrompts}). Mirrors {@link
 * FunnelAnalysisService}'s shape exactly: feature-flagged bean, cache lookup before calling
 * Claude, structured outputs, owner-driven and on-demand only (never a batch job) — see design.md
 * D7 for why this reuses that architecture wholesale rather than inventing a new one.
 */
@Service
public class SeoAiAdvisorService {

    private static final Logger log = LoggerFactory.getLogger(SeoAiAdvisorService.class);

    /** Sonnet, not Haiku — same "low-frequency, owner-triggered consultant task" bar {@link
     * FunnelAnalysisService} and {@code SmsDraftService} already clear. */
    static final String MODEL = "claude-sonnet-5";

    private static final long MAX_OUTPUT_TOKENS = 4096L;

    private static final int OVERVIEW_WINDOW_DAYS = 28;

    private final ObjectProvider<AnthropicClient> anthropicClientProvider;
    private final AiSeoAdvisorProperties props;
    private final SeoAnalysisRepository analyses;
    private final SeoDashboardService dashboardService;
    private final SeoContextBuilderService contextBuilder = new SeoContextBuilderService();

    public SeoAiAdvisorService(ObjectProvider<AnthropicClient> anthropicClientProvider, AiSeoAdvisorProperties props,
            SeoAnalysisRepository analyses, SeoDashboardService dashboardService) {
        this.anthropicClientProvider = anthropicClientProvider;
        this.props = props;
        this.analyses = analyses;
        this.dashboardService = dashboardService;
    }

    /**
     * Analyze the business's current SEO position. Returns {@link Optional#empty()} when the
     * feature is off, or when the business has no {@code seo_connection} at all (nothing to
     * analyze — matches the dashboard's own empty state).
     *
     * @param force when true, skips the cache lookup and always calls Claude fresh, persisting a
     *              new history row even if the underlying data hasn't changed since the last
     *              analysis — the owner-facing "run again anyway" action.
     * @param lang  which language to write the analysis in, resolved by the caller from the
     *              requesting owner/ads-manager's preferred-language setting. Part of the cache
     *              lookup, so switching a user's language never serves a cached result generated
     *              in the other language.
     */
    @Transactional
    public Optional<SeoAnalysisResult> analyze(Long businessId, boolean force, Language lang) {
        if (!props.isEnabled()) return Optional.empty();
        AnthropicClient client = anthropicClientProvider.getIfAvailable();
        if (client == null) return Optional.empty();

        SeoDashboardService.Overview overview = dashboardService.overview(businessId, OVERVIEW_WINDOW_DAYS);
        if (!overview.connected()) return Optional.empty();

        List<SeoAnalysis> priorAnalyses = analyses.findTop3ByBusinessIdOrderByCreatedAtDesc(businessId);
        SeoAnalysisSnapshot snapshot = contextBuilder.build(overview, priorAnalyses);
        String fingerprint = snapshot.toString();

        if (!force) {
            Optional<SeoAnalysis> cached = analyses
                    .findFirstByBusinessIdAndPromptVersionAndSnapshotFingerprintAndLanguageOrderByCreatedAtDesc(
                            businessId, SeoAdvisorPrompts.PROMPT_VERSION, fingerprint, lang);
            if (cached.isPresent()) {
                return Optional.of(SeoAnalysisResult.fromEntity(cached.get()));
            }
        }

        String userMessage = SeoAdvisorPrompts.buildUserMessage(snapshot);
        SeoAnalysisResult result;
        try {
            result = callClaude(client, userMessage, lang);
        } catch (RefusalException re) {
            log.warn("SEO analysis refused for business {}: {}", businessId, re.category());
            result = refusalFallback(re.category(), lang);
        } catch (Exception e) {
            log.error("Claude SEO analysis failed for business {}: {}", businessId, e.toString());
            throw new AnalysisFailedException("LLM call failed", e);
        }

        SeoAnalysis saved = persist(businessId, fingerprint, snapshot, result, lang);
        return Optional.of(SeoAnalysisResult.fromEntity(saved));
    }

    /** Past analyses for this business, newest first, for the owner-facing history list. Empty
     * (not 404) when the feature is disabled or nothing's been analyzed yet — the caller
     * distinguishes "feature off" via {@link AiSeoAdvisorProperties#isEnabled()} directly. */
    public List<SeoAnalysisResult> history(Long businessId) {
        if (!props.isEnabled()) return List.of();
        return analyses.findTop20ByBusinessIdOrderByCreatedAtDesc(businessId).stream()
                .map(SeoAnalysisResult::fromEntity)
                .toList();
    }

    /** Single Claude call with structured-output enforcement — same mechanism as {@link
     * FunnelAnalysisService#callClaude}. The language directive (if any) rides in its own,
     * uncached system block after the cached base prompt, so English requests (the common case)
     * keep a stable cached prefix. Package-private so tests can override. */
    SeoAnalysisResult callClaude(AnthropicClient client, String userMessage, Language lang) throws RefusalException {
        List<TextBlockParam> system = new ArrayList<>();
        system.add(TextBlockParam.builder()
                .text(SeoAdvisorPrompts.SYSTEM_PROMPT_V1)
                .cacheControl(CacheControlEphemeral.builder().build())
                .build());
        String directive = SeoAdvisorPrompts.languageDirective(lang);
        if (directive != null) {
            system.add(TextBlockParam.builder().text(directive).build());
        }

        StructuredMessageCreateParams<SeoAnalysisResult> params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(MAX_OUTPUT_TOKENS)
                .systemOfTextBlockParams(system)
                .addUserMessage(userMessage)
                .outputConfig(SeoAnalysisResult.class)
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

        SeoAnalysisResult parsed = firstTyped.text();
        // createdAt is a placeholder here — the caller always re-wraps the persisted entity via
        // fromEntity(saved) before returning to the controller, which fills in the real timestamp.
        return new SeoAnalysisResult(
                parsed.overallStatus(),
                parsed.executiveSummary(),
                parsed.wins(),
                parsed.problems(),
                parsed.recommendations(),
                SeoAdvisorPrompts.PROMPT_VERSION,
                MODEL,
                null);
    }

    private SeoAnalysisResult refusalFallback(String category, Language lang) {
        String explanation = lang == Language.RU
                ? "Не удалось автоматически проанализировать SEO (" + category + "). "
                        + "Пожалуйста, просмотрите панель вручную."
                : "This SEO analysis couldn't run automatically (" + category + "). Please review the dashboard manually.";
        return new SeoAnalysisResult(
                SeoAnalysisResult.OverallStatus.NEEDS_ATTENTION,
                explanation,
                List.of(),
                List.of(),
                List.of(),
                SeoAdvisorPrompts.PROMPT_VERSION,
                MODEL,
                null);
    }

    private SeoAnalysis persist(Long businessId, String fingerprint, SeoAnalysisSnapshot snapshot,
            SeoAnalysisResult result, Language lang) {
        SeoAnalysis row = SeoAnalysis.builder()
                .businessId(businessId)
                .promptVersion(SeoAdvisorPrompts.PROMPT_VERSION)
                .snapshotFingerprint(fingerprint)
                .language(lang)
                .dataSnapshot(snapshot)
                .overallStatus(result.overallStatus())
                .executiveSummary(result.executiveSummary())
                .wins(result.wins())
                .problems(result.problems())
                .recommendations(result.recommendations())
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
