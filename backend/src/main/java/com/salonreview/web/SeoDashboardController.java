package com.salonreview.web;

import com.salonreview.config.BusinessFeatureService;
import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.domain.SeoTrackedKeyword;
import com.salonreview.seo.SeoChangeDetectionService;
import com.salonreview.seo.SeoPageAnalysisService;
import com.salonreview.seo.SeoDashboardService;
import com.salonreview.seo.SeoDashboardService.AnalyticsPoint;
import com.salonreview.seo.SeoDashboardService.CoreWebVitals;
import com.salonreview.seo.SeoDashboardService.IssueRow;
import com.salonreview.seo.SeoDashboardService.KeywordRow;
import com.salonreview.seo.SeoDashboardService.Overview;
import com.salonreview.seo.SeoDashboardService.TrackedQueryRow;
import com.salonreview.seo.SeoDashboardService.TrendPoint;
import com.salonreview.seo.SeoSyncService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

/**
 * Backs the `/owner/marketing/seo` dashboard tab (Phase 8). GET falls under the existing
 * {@code /api/owner/marketing/**} security matcher (OWNER + ADS_MANAGER, read-only — see
 * SecurityConfig); POST /sync isn't listed there so it falls through to the OWNER-only catch-all,
 * matching design.md 6.2's "owner-only, same shape as the Square sync button" intent (this hits
 * live Google API quotas, unlike a read of already-stored data).
 *
 * <p>404s when {@code seo-monitoring.enabled} is off for the calling business — matches spec.md's
 * "A business without the feature enabled sees no SEO tab" scenario exactly (the frontend simply
 * never renders the tab in that case, per design.md D6, but the API enforces it independently
 * rather than trusting the frontend to hide it).
 */
@RestController
@RequestMapping("/api/owner/marketing/seo")
public class SeoDashboardController {

    private static final int DEFAULT_TREND_DAYS = 28;

    private final SeoDashboardService dashboardService;
    private final SeoSyncService syncService;
    private final BusinessFeatureService businessFeatures;
    private final CurrentBusinessContext currentBusinessContext;

    public SeoDashboardController(SeoDashboardService dashboardService, SeoSyncService syncService,
            BusinessFeatureService businessFeatures, CurrentBusinessContext currentBusinessContext) {
        this.dashboardService = dashboardService;
        this.syncService = syncService;
        this.businessFeatures = businessFeatures;
        this.currentBusinessContext = currentBusinessContext;
    }

    @GetMapping("/overview")
    public SeoOverviewDto overview(@RequestParam(required = false, defaultValue = "" + DEFAULT_TREND_DAYS) int days) {
        Long businessId = requireFeatureEnabled();
        return toDto(dashboardService.overview(businessId, days));
    }

    @PostMapping("/sync")
    public SeoOverviewDto sync() {
        Long businessId = requireFeatureEnabled();
        syncService.syncSearchConsole(businessId);
        syncService.syncAnalytics(businessId);
        syncService.syncPageSpeed(businessId);
        return toDto(dashboardService.overview(businessId, DEFAULT_TREND_DAYS));
    }

    /** Falls through to the {@code /api/owner/**} OWNER-only catch-all, same as {@code /sync} —
     * curating which queries matter is an owner decision, not something ADS_MANAGER's read-only
     * grant covers. Blank/whitespace-only query is rejected here rather than in the service, so a
     * bad request never reaches the repository at all. */
    @PostMapping("/tracked-queries")
    public SeoOverviewDto addTrackedQuery(@RequestBody TrackedQueryRequest request) {
        Long businessId = requireFeatureEnabled();
        String query = request.query() == null ? "" : request.query().trim();
        if (query.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query must not be blank");
        }
        dashboardService.addTrackedQuery(businessId, query);
        return toDto(dashboardService.overview(businessId, DEFAULT_TREND_DAYS));
    }

    @DeleteMapping("/tracked-queries")
    public SeoOverviewDto removeTrackedQuery(@RequestParam String query) {
        Long businessId = requireFeatureEnabled();
        dashboardService.removeTrackedQuery(businessId, query);
        return toDto(dashboardService.overview(businessId, DEFAULT_TREND_DAYS));
    }

    /** Owner-curated keyword rank-tracking list (seo-intelligence-advisor Phase 4) — no real rank
     * check happens yet (Phase 5), this only lets the owner build the list. Same owner-only,
     * blank-rejected-here convention as {@code /tracked-queries}. */
    @PostMapping("/tracked-keywords")
    public SeoOverviewDto addTrackedKeyword(@RequestBody TrackedKeywordRequest request) {
        Long businessId = requireFeatureEnabled();
        String keyword = request.keyword() == null ? "" : request.keyword().trim();
        String location = request.location() == null ? "" : request.location().trim();
        if (keyword.isEmpty() || location.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "keyword and location must not be blank");
        }
        SeoTrackedKeyword.Device device;
        try {
            device = SeoTrackedKeyword.Device.valueOf(
                    (request.device() == null ? "MOBILE" : request.device()).toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "device must be MOBILE or DESKTOP");
        }
        String targetUrl = request.targetUrl() == null || request.targetUrl().isBlank() ? null : request.targetUrl().trim();
        dashboardService.addTrackedKeyword(businessId, keyword, location, device, targetUrl);
        return toDto(dashboardService.overview(businessId, DEFAULT_TREND_DAYS));
    }

    @DeleteMapping("/tracked-keywords/{id}")
    public SeoOverviewDto removeTrackedKeyword(@PathVariable Long id) {
        Long businessId = requireFeatureEnabled();
        dashboardService.removeTrackedKeyword(businessId, id);
        return toDto(dashboardService.overview(businessId, DEFAULT_TREND_DAYS));
    }

    private Long requireFeatureEnabled() {
        Long businessId = currentBusinessContext.id();
        if (!businessFeatures.isEnabled(businessId, BusinessFeatureService.SEO_MONITORING_ENABLED)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "SEO monitoring is not enabled for this business");
        }
        return businessId;
    }

    private static SeoOverviewDto toDto(Overview o) {
        return new SeoOverviewDto(o.connected(), o.lastSyncAt(), o.lastSyncError(),
                o.trend().stream().map(SeoDashboardController::toDto).toList(),
                o.analyticsTrend().stream().map(SeoDashboardController::toDto).toList(),
                o.topQueries().stream().map(SeoDashboardController::toDto).toList(),
                o.trackedQueries().stream().map(SeoDashboardController::toDto).toList(),
                toDto(o.mobile()), toDto(o.desktop()),
                o.activeIssues().stream().map(SeoDashboardController::toDto).toList(),
                toDto(o.last7Days()), toDto(o.last28Days()), toDto(o.yearOverYear()),
                o.gainers().stream().map(SeoDashboardController::toDto).toList(),
                o.losers().stream().map(SeoDashboardController::toDto).toList(),
                o.opportunities().stream().map(SeoDashboardController::toDto).toList(),
                o.winningPages().stream().map(SeoDashboardController::toDto).toList(),
                o.losingPages().stream().map(SeoDashboardController::toDto).toList(),
                o.underperformingPages().stream().map(SeoDashboardController::toDto).toList(),
                o.contentOpportunities().stream().map(SeoDashboardController::toDto).toList(),
                o.cannibalizedQueries().stream().map(SeoDashboardController::toDto).toList(),
                o.trackedKeywords().stream().map(SeoDashboardController::toDto).toList());
    }

    private static TrackedKeywordRowDto toDto(SeoDashboardService.TrackedKeywordRow k) {
        return new TrackedKeywordRowDto(k.id(), k.keyword(), k.targetUrl(), k.location(), k.device(), k.active());
    }

    private static PeriodComparisonDto toDto(SeoDashboardService.PeriodComparison c) {
        if (c == null) return null;
        return new PeriodComparisonDto(toDto(c.current()), toDto(c.previous()));
    }

    private static PageChangeDto toDto(SeoPageAnalysisService.PageChange p) {
        return new PageChangeDto(p.page(), p.previousImpressions(), p.currentImpressions(),
                p.previousClicks(), p.currentClicks(), p.changeRatio());
    }

    private static PageOpportunityDto toDto(SeoPageAnalysisService.PageOpportunity p) {
        return new PageOpportunityDto(p.page(), p.currentPosition(), p.currentImpressions());
    }

    private static CannibalizedQueryDto toDto(SeoPageAnalysisService.CannibalizedQuery c) {
        return new CannibalizedQueryDto(c.query(), c.pages().stream().map(SeoDashboardController::toDto).toList());
    }

    private static PageShareDto toDto(SeoPageAnalysisService.PageShare s) {
        return new PageShareDto(s.page(), s.impressions(), s.share(), s.position());
    }

    private static QueryChangeDto toDto(SeoChangeDetectionService.QueryChange c) {
        return new QueryChangeDto(c.query(), c.previousPosition(), c.currentPosition(), c.positionDelta(),
                c.previousImpressions(), c.currentImpressions(), c.previousClicks(), c.currentClicks());
    }

    private static OpportunityDto toDto(SeoChangeDetectionService.Opportunity o) {
        return new OpportunityDto(o.query(), o.currentPosition(), o.currentImpressions(), o.currentCtr(),
                o.reason().name());
    }

    private static TrendPointDto toDto(TrendPoint p) {
        return new TrendPointDto(p.date(), p.clicks(), p.impressions(), p.ctr(), p.position());
    }

    private static AnalyticsPointDto toDto(AnalyticsPoint p) {
        return new AnalyticsPointDto(p.date(), p.totalUsers(), p.newUsers(), p.organicSessions());
    }

    private static KeywordRowDto toDto(KeywordRow k) {
        return new KeywordRowDto(k.query(), k.clicks(), k.impressions(), k.ctr(), k.position());
    }

    private static TrackedQueryRowDto toDto(TrackedQueryRow t) {
        return new TrackedQueryRowDto(t.query(), t.previousPosition(), t.currentPosition(),
                t.positionDelta(), t.currentImpressions(), t.autoSuggested());
    }

    private static CoreWebVitalsDto toDto(CoreWebVitals v) {
        if (v == null) return null;
        return new CoreWebVitalsDto(v.date(), v.performanceScore(), v.lcpMs(), v.cls(), v.fcpMs(), v.tbtMs());
    }

    private static IssueRowDto toDto(IssueRow i) {
        return new IssueRowDto(i.issueType(), i.severity(), i.detail(), i.url(), i.query());
    }

    public record SeoOverviewDto(boolean connected, Instant lastSyncAt, String lastSyncError,
                                  List<TrendPointDto> trend, List<AnalyticsPointDto> analyticsTrend,
                                  List<KeywordRowDto> topQueries, List<TrackedQueryRowDto> trackedQueries,
                                  CoreWebVitalsDto mobile, CoreWebVitalsDto desktop,
                                  List<IssueRowDto> activeIssues,
                                  PeriodComparisonDto last7Days, PeriodComparisonDto last28Days,
                                  PeriodComparisonDto yearOverYear,
                                  List<QueryChangeDto> gainers, List<QueryChangeDto> losers,
                                  List<OpportunityDto> opportunities,
                                  List<PageChangeDto> winningPages, List<PageChangeDto> losingPages,
                                  List<PageOpportunityDto> underperformingPages,
                                  List<PageOpportunityDto> contentOpportunities,
                                  List<CannibalizedQueryDto> cannibalizedQueries,
                                  List<TrackedKeywordRowDto> trackedKeywords) {
    }

    /** {@code device} is {@code SeoTrackedKeyword.Device}'s name. No rank data yet — {@code
     * seo_rank_snapshot} (Phase 5) is keyed by {@code id} once a rank-tracking provider connects. */
    public record TrackedKeywordRowDto(Long id, String keyword, String targetUrl, String location, String device,
                                        boolean active) {
    }

    public record TrackedKeywordRequest(String keyword, String location, String device, String targetUrl) {
    }

    /** {@code changeRatio} is {@code (current - previous) / previous}: positive means growth. */
    public record PageChangeDto(String page, long previousImpressions, long currentImpressions,
                                 long previousClicks, long currentClicks, java.math.BigDecimal changeRatio) {
    }

    public record PageOpportunityDto(String page, java.math.BigDecimal currentPosition, long currentImpressions) {
    }

    /** {@code pages} is sorted by impressions descending — the frontend can render the first entry
     * as the presumed "intended" page without asserting it. */
    public record CannibalizedQueryDto(String query, List<PageShareDto> pages) {
    }

    public record PageShareDto(String page, long impressions, java.math.BigDecimal share,
                                java.math.BigDecimal position) {
    }

    /** {@code previous} is {@code null} when there's no data for the equivalent prior period —
     * the frontend omits the comparison entirely rather than showing a fabricated baseline. */
    public record PeriodComparisonDto(TrendPointDto current, TrendPointDto previous) {
    }

    /** {@code positionDelta = previousPosition - currentPosition}: positive means improved (same
     * sign convention as {@code TrackedQueryRowDto}). */
    public record QueryChangeDto(String query, java.math.BigDecimal previousPosition,
                                  java.math.BigDecimal currentPosition, java.math.BigDecimal positionDelta,
                                  long previousImpressions, long currentImpressions,
                                  long previousClicks, long currentClicks) {
    }

    /** {@code reason} is one of {@code SeoChangeDetectionService.OpportunityReason}'s names
     * ("STRIKING_DISTANCE", "HIGH_IMPRESSIONS_LOW_CTR", "GROWING_IMPRESSIONS"). */
    public record OpportunityDto(String query, java.math.BigDecimal currentPosition, long currentImpressions,
                                  java.math.BigDecimal currentCtr, String reason) {
    }

    public record TrendPointDto(java.time.LocalDate date, long clicks, long impressions,
                                 java.math.BigDecimal ctr, java.math.BigDecimal position) {
    }

    public record AnalyticsPointDto(java.time.LocalDate date, long totalUsers, long newUsers, long organicSessions) {
    }

    public record KeywordRowDto(String query, long clicks, long impressions,
                                 java.math.BigDecimal ctr, java.math.BigDecimal position) {
    }

    /** {@code previousPosition}/{@code currentPosition}/{@code positionDelta} are {@code null}
     * when there's no data for one half of the window yet (see {@code SeoDashboardService
     * .TrackedQueryRow}'s own doc comment for the sign convention). */
    public record TrackedQueryRowDto(String query, java.math.BigDecimal previousPosition,
                                      java.math.BigDecimal currentPosition, java.math.BigDecimal positionDelta,
                                      long currentImpressions, boolean autoSuggested) {
    }

    public record TrackedQueryRequest(String query) {
    }

    /** {@code null} when no PageSpeed snapshot exists yet for that strategy (feature just turned
     * on, first weekly check hasn't run) — the frontend shows a "waiting for first sync" state. */
    public record CoreWebVitalsDto(java.time.LocalDate date, Integer performanceScore, Integer lcpMs,
                                    java.math.BigDecimal cls, Integer fcpMs, Integer tbtMs) {
    }

    public record IssueRowDto(String issueType, String severity, String detail, String url, String query) {
    }
}
