package com.salonreview.ai;

import com.anthropic.client.AnthropicClient;
import com.salonreview.config.AiSeoAdvisorProperties;
import com.salonreview.domain.ImpactLevel;
import com.salonreview.domain.Language;
import com.salonreview.domain.SeoAnalysis;
import com.salonreview.repo.SeoAnalysisRepository;
import com.salonreview.seo.SeoDashboardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Follows the same spy-and-override-{@code callClaude} pattern as {@code
 * FunnelAnalysisServiceTest} — the real Anthropic SDK call is never exercised, only this service's
 * own caching/persistence/language logic.
 */
class SeoAiAdvisorServiceTest {

    @SuppressWarnings("unchecked")
    private ObjectProvider<AnthropicClient> anthropicProvider = mock(ObjectProvider.class);
    private AiSeoAdvisorProperties props;
    private SeoAnalysisRepository analyses;
    private SeoDashboardService dashboardService;

    private SeoAiAdvisorService service;
    private SeoAiAdvisorService spied;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        anthropicProvider = mock(ObjectProvider.class);
        when(anthropicProvider.getIfAvailable()).thenReturn(mock(AnthropicClient.class));

        props = mock(AiSeoAdvisorProperties.class);
        when(props.isEnabled()).thenReturn(true);

        analyses = mock(SeoAnalysisRepository.class);
        dashboardService = mock(SeoDashboardService.class);
        when(dashboardService.overview(eq(1L), any(Integer.class))).thenReturn(connectedOverview());
        when(analyses.findTop3ByBusinessIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());

        service = new SeoAiAdvisorService(anthropicProvider, props, analyses, dashboardService);
        spied = spy(service);

        when(analyses.save(any())).thenAnswer(inv -> {
            SeoAnalysis row = inv.getArgument(0);
            if (row.getId() == null) row.setId(1L);
            return row;
        });
    }

    private static SeoDashboardService.Overview connectedOverview() {
        return new SeoDashboardService.Overview(true, null, null, List.of(), List.of(), List.of(), List.of(),
                null, null, List.of(), null, null, null, List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static SeoAnalysisResult canned(SeoAnalysisResult.OverallStatus status) {
        return new SeoAnalysisResult(status, "Summary.",
                List.of("A win"), List.of("A problem"),
                List.of(new SeoAnalysisResult.Recommendation(1, "Action", "Why", "Evidence",
                        ImpactLevel.HIGH, ImpactLevel.LOW, ImpactLevel.HIGH, "How", "/page")),
                "WRONG_VERSION", "WRONG_MODEL", null);
    }

    @Test
    @DisplayName("analyze() returns empty when the business has no seo_connection")
    void returnsEmptyWhenNotConnected() {
        when(dashboardService.overview(eq(1L), any(Integer.class))).thenReturn(
                new SeoDashboardService.Overview(false, null, null, List.of(), List.of(), List.of(), List.of(),
                        null, null, List.of(), null, null, null, List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));

        assertThat(service.analyze(1L, false, Language.EN)).isEmpty();
    }

    @Test
    @DisplayName("an EN-cached row is not served for an RU request — cache miss, LLM called fresh")
    void cacheLookupDistinguishesLanguage() throws Exception {
        when(analyses.findFirstByBusinessIdAndPromptVersionAndSnapshotFingerprintAndLanguageOrderByCreatedAtDesc(
                eq(1L), anyString(), anyString(), eq(Language.RU)))
                .thenReturn(Optional.empty());
        doReturn(canned(SeoAnalysisResult.OverallStatus.NEEDS_ATTENTION)).when(spied).callClaude(any(), anyString(), eq(Language.RU));

        Optional<SeoAnalysisResult> result = spied.analyze(1L, false, Language.RU);

        assertThat(result).isPresent();
        verify(spied).callClaude(any(), anyString(), eq(Language.RU));
    }

    @Test
    @DisplayName("a cached row is returned as-is without calling the LLM")
    void cacheHitSkipsLlm() throws Exception {
        SeoAnalysis cached = SeoAnalysis.builder()
                .id(5L).businessId(1L).promptVersion(SeoAdvisorPrompts.PROMPT_VERSION)
                .snapshotFingerprint("fp").language(Language.EN)
                .overallStatus(SeoAnalysisResult.OverallStatus.HEALTHY)
                .executiveSummary("Cached summary.")
                .wins(List.of()).problems(List.of()).recommendations(List.of())
                .model("claude-sonnet-5")
                .build();
        when(analyses.findFirstByBusinessIdAndPromptVersionAndSnapshotFingerprintAndLanguageOrderByCreatedAtDesc(
                eq(1L), anyString(), anyString(), eq(Language.EN)))
                .thenReturn(Optional.of(cached));

        Optional<SeoAnalysisResult> result = spied.analyze(1L, false, Language.EN);

        assertThat(result).isPresent();
        assertThat(result.get().executiveSummary()).isEqualTo("Cached summary.");
        verify(spied, never()).callClaude(any(), anyString(), any());
    }

    @Test
    @DisplayName("force=true bypasses the cache and calls the LLM even when a cached row exists")
    void forceBypassesCache() throws Exception {
        SeoAnalysis cached = SeoAnalysis.builder()
                .id(5L).businessId(1L).promptVersion(SeoAdvisorPrompts.PROMPT_VERSION)
                .snapshotFingerprint("fp").language(Language.EN)
                .overallStatus(SeoAnalysisResult.OverallStatus.HEALTHY)
                .executiveSummary("Cached summary.")
                .wins(List.of()).problems(List.of()).recommendations(List.of())
                .model("claude-sonnet-5")
                .build();
        when(analyses.findFirstByBusinessIdAndPromptVersionAndSnapshotFingerprintAndLanguageOrderByCreatedAtDesc(
                eq(1L), anyString(), anyString(), eq(Language.EN)))
                .thenReturn(Optional.of(cached));
        doReturn(canned(SeoAnalysisResult.OverallStatus.CRITICAL)).when(spied).callClaude(any(), anyString(), eq(Language.EN));

        Optional<SeoAnalysisResult> result = spied.analyze(1L, true, Language.EN);

        assertThat(result).isPresent();
        verify(spied).callClaude(any(), anyString(), eq(Language.EN));
    }

    @Test
    @DisplayName("persisted row stores the requested language and the full recommendation list")
    void persistsRequestedLanguageAndRecommendations() throws Exception {
        when(analyses.findFirstByBusinessIdAndPromptVersionAndSnapshotFingerprintAndLanguageOrderByCreatedAtDesc(
                eq(1L), anyString(), anyString(), eq(Language.RU)))
                .thenReturn(Optional.empty());
        doReturn(canned(SeoAnalysisResult.OverallStatus.NEEDS_ATTENTION)).when(spied).callClaude(any(), anyString(), eq(Language.RU));

        spied.analyze(1L, false, Language.RU);

        ArgumentCaptor<SeoAnalysis> cap = ArgumentCaptor.forClass(SeoAnalysis.class);
        verify(analyses).save(cap.capture());
        assertThat(cap.getValue().getLanguage()).isEqualTo(Language.RU);
        assertThat(cap.getValue().getRecommendations()).hasSize(1);
        assertThat(cap.getValue().getModel()).isEqualTo(SeoAiAdvisorService.MODEL);
    }

    @Test
    @DisplayName("a refusal falls back to Russian explanation text when Russian was requested")
    void refusalFallsBackToRussianText() throws Exception {
        when(analyses.findFirstByBusinessIdAndPromptVersionAndSnapshotFingerprintAndLanguageOrderByCreatedAtDesc(
                eq(1L), anyString(), anyString(), eq(Language.RU)))
                .thenReturn(Optional.empty());
        doThrow(new SeoAiAdvisorService.RefusalException("policy_refusal"))
                .when(spied).callClaude(any(), anyString(), eq(Language.RU));

        Optional<SeoAnalysisResult> result = spied.analyze(1L, false, Language.RU);

        assertThat(result).isPresent();
        assertThat(result.get().executiveSummary()).contains("Не удалось автоматически проанализировать");
    }

    @Test
    @DisplayName("history() returns an empty list, not an error, when nothing's been analyzed yet")
    void historyEmptyWhenNothingAnalyzed() {
        when(analyses.findTop20ByBusinessIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());

        assertThat(service.history(1L)).isEmpty();
    }

    @Test
    @DisplayName("history() returns an empty list when the feature is disabled, not an error")
    void historyEmptyWhenDisabled() {
        when(props.isEnabled()).thenReturn(false);

        assertThat(service.history(1L)).isEmpty();
    }
}
