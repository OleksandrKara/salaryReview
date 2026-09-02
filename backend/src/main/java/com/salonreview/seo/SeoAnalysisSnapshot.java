package com.salonreview.seo;

import java.time.Instant;
import java.util.List;

/**
 * The bounded, prioritized structured snapshot fed to the SEO AI Advisor (seo-intelligence-advisor
 * design.md D8) — never unbounded raw rows. Reuses {@code SeoDashboardService}/{@code
 * SeoChangeDetectionService}/{@code SeoPageAnalysisService}'s own record types directly rather
 * than duplicating parallel "snapshot" DTOs for each: those lists are already capped at a sensible
 * size (each service's own {@code MAX_RESULTS = 20}) and already ranked by significance, which is
 * exactly what design.md's context-budget requirement asks for — introducing a second, separate
 * set of budget constants here would just be two numbers to keep in sync for no real benefit.
 *
 * <p>This exact object is both what the LLM call is built from ({@code
 * SeoAdvisorPrompts#buildUserMessage}) and what gets persisted verbatim as {@code
 * seo_analysis.data_snapshot} — so a historical analysis can always be reconstructed exactly,
 * independent of what the live dashboard shows today.
 */
public record SeoAnalysisSnapshot(
        SeoDashboardService.PeriodComparison last7Days,
        SeoDashboardService.PeriodComparison last28Days,
        SeoDashboardService.PeriodComparison yearOverYear,
        List<SeoChangeDetectionService.QueryChange> gainers,
        List<SeoChangeDetectionService.QueryChange> losers,
        List<SeoChangeDetectionService.Opportunity> opportunities,
        List<SeoPageAnalysisService.PageChange> winningPages,
        List<SeoPageAnalysisService.PageChange> losingPages,
        List<SeoPageAnalysisService.PageOpportunity> underperformingPages,
        List<SeoPageAnalysisService.PageOpportunity> contentOpportunities,
        List<SeoPageAnalysisService.CannibalizedQuery> cannibalizedQueries,
        List<SeoDashboardService.TrackedKeywordRow> trackedKeywords,
        List<SeoDashboardService.IssueRow> technicalIssues,
        List<PriorRecommendation> priorAnalyses) {

    /** A one-line summary of a past analysis — not the full recommendation list (that would defeat
     * the point of a context budget); just enough for the model to notice "I already said this
     * last time" and either reinforce or revise it, per design.md's "previous AI recommendations
     * should be available to the system" requirement. */
    public record PriorRecommendation(Instant createdAt, String overallStatus, String topRecommendation) {}
}
