package com.salonreview.seo;

import com.salonreview.ai.SeoAnalysisResult;
import com.salonreview.domain.ImpactLevel;
import com.salonreview.domain.Language;
import com.salonreview.domain.SeoAnalysis;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SeoContextBuilderServiceTest {

    private final SeoContextBuilderService service = new SeoContextBuilderService();

    private static SeoDashboardService.Overview emptyOverview() {
        return new SeoDashboardService.Overview(true, null, null, List.of(), List.of(), List.of(), List.of(),
                null, null, List.of(), null, null, null, List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    @Test
    @DisplayName("build() carries every overview list straight through into the snapshot")
    void buildReusesOverviewListsDirectly() {
        SeoDashboardService.Overview overview = emptyOverview();

        SeoAnalysisSnapshot snapshot = service.build(overview, List.of());

        assertThat(snapshot.gainers()).isEqualTo(overview.gainers());
        assertThat(snapshot.losers()).isEqualTo(overview.losers());
        assertThat(snapshot.opportunities()).isEqualTo(overview.opportunities());
        assertThat(snapshot.winningPages()).isEqualTo(overview.winningPages());
        assertThat(snapshot.cannibalizedQueries()).isEqualTo(overview.cannibalizedQueries());
        assertThat(snapshot.trackedKeywords()).isEqualTo(overview.trackedKeywords());
        assertThat(snapshot.technicalIssues()).isEqualTo(overview.activeIssues());
    }

    @Test
    @DisplayName("prior analyses are summarized to one line each, not their full recommendation list")
    void priorAnalysesAreSummarized() {
        SeoAnalysis prior = SeoAnalysis.builder()
                .id(1L).businessId(1L).createdAt(Instant.parse("2026-08-01T00:00:00Z"))
                .overallStatus(SeoAnalysisResult.OverallStatus.NEEDS_ATTENTION)
                .recommendations(List.of(
                        new SeoAnalysisResult.Recommendation(1, "Improve title tag", "why", "evidence",
                                ImpactLevel.HIGH, ImpactLevel.LOW, ImpactLevel.HIGH, "how", "/page"),
                        new SeoAnalysisResult.Recommendation(2, "Second thing", "why2", "evidence2",
                                ImpactLevel.MEDIUM, ImpactLevel.LOW, ImpactLevel.MEDIUM, "how2", null)))
                .language(Language.EN)
                .build();

        SeoAnalysisSnapshot snapshot = service.build(emptyOverview(), List.of(prior));

        assertThat(snapshot.priorAnalyses()).hasSize(1);
        assertThat(snapshot.priorAnalyses().get(0).overallStatus()).isEqualTo("NEEDS_ATTENTION");
        assertThat(snapshot.priorAnalyses().get(0).topRecommendation()).isEqualTo("Improve title tag");
    }

    @Test
    @DisplayName("a prior analysis with no recommendations summarizes topRecommendation as null, not an error")
    void priorAnalysisWithNoRecommendations() {
        SeoAnalysis prior = SeoAnalysis.builder()
                .id(1L).businessId(1L).createdAt(Instant.now())
                .overallStatus(SeoAnalysisResult.OverallStatus.HEALTHY)
                .recommendations(List.of())
                .language(Language.EN)
                .build();

        SeoAnalysisSnapshot snapshot = service.build(emptyOverview(), List.of(prior));

        assertThat(snapshot.priorAnalyses().get(0).topRecommendation()).isNull();
    }
}
