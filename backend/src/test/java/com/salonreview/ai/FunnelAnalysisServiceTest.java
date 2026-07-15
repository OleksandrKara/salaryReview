package com.salonreview.ai;

import com.anthropic.client.AnthropicClient;
import com.salonreview.config.AiFunnelAnalysisProperties;
import com.salonreview.domain.FunnelAnalysis;
import com.salonreview.domain.ImpactLevel;
import com.salonreview.domain.Language;
import com.salonreview.marketing.FunnelAnalyticsService;
import com.salonreview.repo.FunnelAnalysisRepository;
import com.salonreview.web.dto.FunnelDashboardDto;
import com.salonreview.web.dto.FunnelDashboardDto.FunnelStepStat;
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
 * Unit tests for {@link FunnelAnalysisService}'s Russian-language support — the cache lookup must
 * distinguish EN/RU results for the same funnel snapshot, and a refusal must fall back to Russian
 * text when Russian was requested. Follows the same spy-and-override-callClaude pattern as
 * {@link SuspiciousBookingTriageServiceTest}.
 */
class FunnelAnalysisServiceTest {

    @SuppressWarnings("unchecked")
    private ObjectProvider<AnthropicClient> anthropicProvider = mock(ObjectProvider.class);
    private AiFunnelAnalysisProperties props;
    private FunnelAnalysisRepository analyses;
    private FunnelAnalyticsService funnelAnalyticsService;

    private FunnelAnalysisService service;
    private FunnelAnalysisService spied;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        anthropicProvider = mock(ObjectProvider.class);
        when(anthropicProvider.getIfAvailable()).thenReturn(mock(AnthropicClient.class));

        props = mock(AiFunnelAnalysisProperties.class);
        when(props.isEnabled()).thenReturn(true);

        analyses = mock(FunnelAnalysisRepository.class);
        funnelAnalyticsService = mock(FunnelAnalyticsService.class);

        service = new FunnelAnalysisService(anthropicProvider, props, analyses, funnelAnalyticsService);
        spied = spy(service);

        when(analyses.save(any())).thenAnswer(inv -> {
            FunnelAnalysis row = inv.getArgument(0);
            if (row.getId() == null) row.setId(1L);
            return row;
        });

        when(funnelAnalyticsService.funnel(eq("home"), any())).thenReturn(List.of(sampleFunnel()));
    }

    private static FunnelDashboardDto sampleFunnel() {
        return new FunnelDashboardDto("home", "homepage_booking_v1", 100, 60,
                List.of(new FunnelStepStat("services", 0, 4, 60, 0.6, 40, 0.4),
                        new FunnelStepStat("addons", 1, 4, 30, 0.3, 30, 0.5)),
                20, 0.2);
    }

    private static FunnelAnalysisResult canned(String bottleneck) {
        return new FunnelAnalysisResult(
                bottleneck, "Explanation.",
                List.of(new FunnelAnalysisResult.PrioritizedRecommendation("Title", "Rationale.", ImpactLevel.HIGH)),
                List.of(), List.of("Test idea"), "Do this first.", "WRONG_VERSION", "WRONG_MODEL", null);
    }

    @Test
    @DisplayName("an EN-cached row is not served for an RU request — cache miss, LLM called fresh")
    void cacheLookupDistinguishesLanguage() throws Exception {
        // No cached row for RU (only the EN lookup would have hit, if it were checked).
        when(analyses.findFirstByLandingPageSlugAndFlowKeyAndPromptVersionAndSnapshotFingerprintAndLanguageOrderByCreatedAtDesc(
                anyString(), anyString(), anyString(), anyString(), eq(Language.RU)))
                .thenReturn(Optional.empty());
        doReturn(canned("addons")).when(spied).callClaude(any(), anyString(), eq(Language.RU));

        Optional<FunnelAnalysisResult> result = spied.analyze("home", "homepage_booking_v1", true, false, Language.RU);

        assertThat(result).isPresent();
        verify(spied).callClaude(any(), anyString(), eq(Language.RU));
        verify(analyses).findFirstByLandingPageSlugAndFlowKeyAndPromptVersionAndSnapshotFingerprintAndLanguageOrderByCreatedAtDesc(
                eq("home"), eq("homepage_booking_v1"), eq(FunnelAnalysisPrompts.PROMPT_VERSION), anyString(), eq(Language.RU));
    }

    @Test
    @DisplayName("a cached RU row is returned as-is without calling the LLM")
    void cacheHitSkipsLlmForRu() throws Exception {
        FunnelAnalysis cached = FunnelAnalysis.builder()
                .id(5L).landingPageSlug("home").flowKey("homepage_booking_v1")
                .promptVersion(FunnelAnalysisPrompts.PROMPT_VERSION).snapshotFingerprint("fp").language(Language.RU)
                .biggestBottleneckStep("addons").bottleneckExplanation("Кэшированное объяснение.")
                .recommendations(List.of()).suspiciousPatterns(List.of()).suggestedAbTests(List.of())
                .topPriorityAction("Сделайте это.").model("claude-sonnet-5")
                .build();
        when(analyses.findFirstByLandingPageSlugAndFlowKeyAndPromptVersionAndSnapshotFingerprintAndLanguageOrderByCreatedAtDesc(
                anyString(), anyString(), anyString(), anyString(), eq(Language.RU)))
                .thenReturn(Optional.of(cached));

        Optional<FunnelAnalysisResult> result = spied.analyze("home", "homepage_booking_v1", true, false, Language.RU);

        assertThat(result).isPresent();
        assertThat(result.get().bottleneckExplanation()).isEqualTo("Кэшированное объяснение.");
        verify(spied, never()).callClaude(any(), anyString(), any());
    }

    @Test
    @DisplayName("persisted row stores the requested language")
    void persistsRequestedLanguage() throws Exception {
        when(analyses.findFirstByLandingPageSlugAndFlowKeyAndPromptVersionAndSnapshotFingerprintAndLanguageOrderByCreatedAtDesc(
                anyString(), anyString(), anyString(), anyString(), eq(Language.RU)))
                .thenReturn(Optional.empty());
        doReturn(canned("addons")).when(spied).callClaude(any(), anyString(), eq(Language.RU));

        spied.analyze("home", "homepage_booking_v1", true, false, Language.RU);

        ArgumentCaptor<FunnelAnalysis> cap = ArgumentCaptor.forClass(FunnelAnalysis.class);
        verify(analyses).save(cap.capture());
        assertThat(cap.getValue().getLanguage()).isEqualTo(Language.RU);
    }

    @Test
    @DisplayName("a refusal falls back to Russian explanation/action text when Russian was requested")
    void refusalFallsBackToRussianText() throws Exception {
        when(analyses.findFirstByLandingPageSlugAndFlowKeyAndPromptVersionAndSnapshotFingerprintAndLanguageOrderByCreatedAtDesc(
                anyString(), anyString(), anyString(), anyString(), eq(Language.RU)))
                .thenReturn(Optional.empty());
        doThrow(new FunnelAnalysisService.RefusalException("policy_refusal"))
                .when(spied).callClaude(any(), anyString(), eq(Language.RU));

        Optional<FunnelAnalysisResult> result = spied.analyze("home", "homepage_booking_v1", true, false, Language.RU);

        assertThat(result).isPresent();
        assertThat(result.get().bottleneckExplanation()).contains("Не удалось автоматически проанализировать");
        assertThat(result.get().topPriorityAction()).isEqualTo("Просмотрите цифры воронки вручную.");
    }

    @Test
    @DisplayName("a refusal falls back to English text when English was requested")
    void refusalFallsBackToEnglishText() throws Exception {
        when(analyses.findFirstByLandingPageSlugAndFlowKeyAndPromptVersionAndSnapshotFingerprintAndLanguageOrderByCreatedAtDesc(
                anyString(), anyString(), anyString(), anyString(), eq(Language.EN)))
                .thenReturn(Optional.empty());
        doThrow(new FunnelAnalysisService.RefusalException("policy_refusal"))
                .when(spied).callClaude(any(), anyString(), eq(Language.EN));

        Optional<FunnelAnalysisResult> result = spied.analyze("home", "homepage_booking_v1", true, false, Language.EN);

        assertThat(result).isPresent();
        assertThat(result.get().bottleneckExplanation()).contains("couldn't be analyzed automatically");
        assertThat(result.get().topPriorityAction()).isEqualTo("Review the funnel numbers manually.");
    }
}
