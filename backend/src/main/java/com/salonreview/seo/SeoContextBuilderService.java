package com.salonreview.seo;

import com.salonreview.domain.SeoAnalysis;

import java.util.List;

/**
 * Builds the bounded, prioritized {@link SeoAnalysisSnapshot} fed to the SEO AI Advisor
 * (seo-intelligence-advisor design.md D8). The "aggregation → filtering → ranking →
 * prioritization" pipeline the proposal asked for already happened when {@link
 * SeoDashboardService#overview} was built — {@link SeoChangeDetectionService}/{@link
 * SeoPageAnalysisService} already rank and cap every list they return — so this class's real job
 * is narrower: reuse that already-bounded overview, and fold in a short summary of prior analyses
 * (design.md's "previous AI recommendations should be available to the system") without pulling
 * in their full recommendation lists, which would spend the context budget on old output instead
 * of current data.
 */
public class SeoContextBuilderService {

    public SeoAnalysisSnapshot build(SeoDashboardService.Overview overview, List<SeoAnalysis> priorAnalyses) {
        List<SeoAnalysisSnapshot.PriorRecommendation> priorSummaries = priorAnalyses.stream()
                .map(a -> new SeoAnalysisSnapshot.PriorRecommendation(
                        a.getCreatedAt(),
                        a.getOverallStatus().name(),
                        a.getRecommendations().isEmpty() ? null : a.getRecommendations().get(0).action()))
                .toList();

        return new SeoAnalysisSnapshot(
                overview.last7Days(), overview.last28Days(), overview.yearOverYear(),
                overview.gainers(), overview.losers(), overview.opportunities(),
                overview.winningPages(), overview.losingPages(),
                overview.underperformingPages(), overview.contentOpportunities(),
                overview.cannibalizedQueries(), overview.trackedKeywords(), overview.activeIssues(),
                priorSummaries);
    }
}
