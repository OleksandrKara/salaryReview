package com.salonreview.web;

import com.salonreview.config.BusinessFeatureService;
import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.seo.SeoDashboardService;
import com.salonreview.seo.SeoDashboardService.CoreWebVitals;
import com.salonreview.seo.SeoDashboardService.IssueRow;
import com.salonreview.seo.SeoDashboardService.KeywordRow;
import com.salonreview.seo.SeoDashboardService.Overview;
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
        syncService.syncPageSpeed(businessId);
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
                o.topQueries().stream().map(SeoDashboardController::toDto).toList(),
                toDto(o.mobile()), toDto(o.desktop()),
                o.activeIssues().stream().map(SeoDashboardController::toDto).toList());
    }

    private static TrendPointDto toDto(TrendPoint p) {
        return new TrendPointDto(p.date(), p.clicks(), p.impressions(), p.ctr(), p.position());
    }

    private static KeywordRowDto toDto(KeywordRow k) {
        return new KeywordRowDto(k.query(), k.clicks(), k.impressions(), k.ctr(), k.position());
    }

    private static CoreWebVitalsDto toDto(CoreWebVitals v) {
        if (v == null) return null;
        return new CoreWebVitalsDto(v.date(), v.performanceScore(), v.lcpMs(), v.cls(), v.fcpMs(), v.tbtMs());
    }

    private static IssueRowDto toDto(IssueRow i) {
        return new IssueRowDto(i.issueType(), i.severity(), i.detail(), i.url(), i.query());
    }

    public record SeoOverviewDto(boolean connected, Instant lastSyncAt, String lastSyncError,
                                  List<TrendPointDto> trend, List<KeywordRowDto> topQueries,
                                  CoreWebVitalsDto mobile, CoreWebVitalsDto desktop,
                                  List<IssueRowDto> activeIssues) {
    }

    public record TrendPointDto(java.time.LocalDate date, long clicks, long impressions,
                                 java.math.BigDecimal ctr, java.math.BigDecimal position) {
    }

    public record KeywordRowDto(String query, long clicks, long impressions,
                                 java.math.BigDecimal ctr, java.math.BigDecimal position) {
    }

    /** {@code null} when no PageSpeed snapshot exists yet for that strategy (feature just turned
     * on, first weekly check hasn't run) — the frontend shows a "waiting for first sync" state. */
    public record CoreWebVitalsDto(java.time.LocalDate date, Integer performanceScore, Integer lcpMs,
                                    java.math.BigDecimal cls, Integer fcpMs, Integer tbtMs) {
    }

    public record IssueRowDto(String issueType, String severity, String detail, String url, String query) {
    }
}
