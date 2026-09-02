package com.salonreview.seo;

import com.salonreview.domain.SeoAnalyticsSnapshot;
import com.salonreview.domain.SeoCompetitor;
import com.salonreview.domain.SeoCompetitorPageSnapshot;
import com.salonreview.domain.SeoConnection;
import com.salonreview.domain.SeoPageSnapshot;
import com.salonreview.domain.SeoSearchMetricsSnapshot;
import com.salonreview.repo.SeoAnalyticsSnapshotRepository;
import com.salonreview.repo.SeoCompetitorPageSnapshotRepository;
import com.salonreview.repo.SeoCompetitorRepository;
import com.salonreview.repo.SeoConnectionRepository;
import com.salonreview.repo.SeoPageSnapshotRepository;
import com.salonreview.repo.SeoSearchMetricsSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The actual sync logic shared by both scheduled jobs ({@link SeoSearchConsoleSyncScheduler}/
 * {@link SeoPageSpeedSyncScheduler}) and the manual "sync now" button
 * ({@code SeoDashboardController}) — one place that builds a fresh per-business API client,
 * upserts the resulting rows, runs {@link SeoIssueFlaggingService}, and records success/failure on
 * {@link SeoConnection}, so the manual button can never drift from what the schedule does.
 */
@Service
public class SeoSyncService {

    private static final Logger log = LoggerFactory.getLogger(SeoSyncService.class);

    // Real diagnostic against AK.LUX.NAILS' live account (2026-09-01) found data can already be
    // final as recently as 2 days ago while slightly-older days in between stay empty — Search
    // Console's own "2-3 day lag" guidance is not a hard floor to skip under, and for a low-traffic
    // property whole days can legitimately have zero query-level rows regardless of lag. So this
    // queries every day through today, not a lagged end date — a day too fresh to have data yet
    // just costs one API call that returns an empty list, not an error.
    //
    // Windowed to 28 days (matching SeoDashboardService.DEFAULT_TREND_DAYS) and re-upserted on
    // every sync, not just the newest day, so the trend chart is fully populated from the very
    // first sync and any day GSC hadn't finalized on a prior run self-heals/backfills — same
    // rolling-window reconciliation idea SquareMirrorReconciliationScheduler already uses for the
    // same reason.
    private static final int SEARCH_CONSOLE_WINDOW_DAYS = 28;
    private static final int SEARCH_CONSOLE_ROW_LIMIT = 200;

    // Same 28-day rolling window as Search Console (SEARCH_CONSOLE_WINDOW_DAYS) — one GA4 property
    // is cheap to query daily (design.md Risks singles out PageSpeed's quota as the tight one, not
    // GA4's), and re-upserting the whole window every sync self-heals the same way.
    private static final int ANALYTICS_WINDOW_DAYS = 28;

    private final SeoConnectionRepository connectionRepository;
    private final SeoConnectionService connectionService;
    private final SeoSearchMetricsSnapshotRepository searchMetricsRepository;
    private final SeoPageSnapshotRepository pageSnapshotRepository;
    private final SeoAnalyticsSnapshotRepository analyticsSnapshotRepository;
    private final SeoIssueFlaggingService flaggingService;
    private final SeoCompetitorRepository competitorRepository;
    private final SeoCompetitorPageSnapshotRepository competitorPageSnapshotRepository;

    public SeoSyncService(SeoConnectionRepository connectionRepository, SeoConnectionService connectionService,
            SeoSearchMetricsSnapshotRepository searchMetricsRepository,
            SeoPageSnapshotRepository pageSnapshotRepository,
            SeoAnalyticsSnapshotRepository analyticsSnapshotRepository, SeoIssueFlaggingService flaggingService,
            SeoCompetitorRepository competitorRepository,
            SeoCompetitorPageSnapshotRepository competitorPageSnapshotRepository) {
        this.connectionRepository = connectionRepository;
        this.connectionService = connectionService;
        this.searchMetricsRepository = searchMetricsRepository;
        this.pageSnapshotRepository = pageSnapshotRepository;
        this.competitorRepository = competitorRepository;
        this.competitorPageSnapshotRepository = competitorPageSnapshotRepository;
        this.analyticsSnapshotRepository = analyticsSnapshotRepository;
        this.flaggingService = flaggingService;
    }

    /** No-op (not an error) when the business hasn't connected credentials yet — callers (the
     * scheduler's per-connection loop, or the manual-sync endpoint after checking {@code
     * SeoConnectionService.get} isn't empty) already only call this for businesses that have one. */
    @Transactional
    public void syncSearchConsole(Long businessId) {
        SeoConnection connection = connectionRepository.findByBusinessId(businessId).orElse(null);
        if (connection == null) return;

        try {
            SearchConsoleClient client = new SearchConsoleClient(connectionService.decryptedServiceAccountJson(connection));
            List<SearchConsoleClient.Site> sites = client.sites();
            if (sites.isEmpty()) {
                throw new IllegalStateException("Service account has no visible Search Console sites");
            }
            String siteUrl = sites.get(0).siteUrl();
            LocalDate endDate = LocalDate.now(ZoneOffset.UTC);
            LocalDate startDate = endDate.minusDays(SEARCH_CONSOLE_WINDOW_DAYS - 1);

            // One call for the whole window (rowLimit sized for every day to independently hit the
            // per-day cap), not a call per day — see SearchConsoleClient.queryPerformance's own
            // doc comment for why.
            List<SeoSearchMetricsSnapshot> saved = new ArrayList<>();
            for (SearchConsoleClient.QueryRow row : client.queryPerformance(siteUrl, startDate, endDate,
                    SEARCH_CONSOLE_ROW_LIMIT * SEARCH_CONSOLE_WINDOW_DAYS)) {
                SeoSearchMetricsSnapshot snapshot = searchMetricsRepository
                        .findByBusinessIdAndDateAndQueryAndPage(businessId, row.date(), row.query(), row.page())
                        .orElseGet(SeoSearchMetricsSnapshot::new);
                snapshot.setBusinessId(businessId);
                snapshot.setDate(row.date());
                snapshot.setQuery(row.query());
                snapshot.setPage(row.page());
                snapshot.setClicks((int) row.clicks());
                snapshot.setImpressions((int) row.impressions());
                snapshot.setCtr(row.ctr());
                snapshot.setPosition(row.position());
                saved.add(searchMetricsRepository.save(snapshot));
            }
            // Evaluated once across the whole window, not per-day — the CTR heuristic's trailing
            // average needs the full window to be a meaningful signal (see
            // SeoIssueFlaggingService#evaluateSearchMetrics's own doc comment).
            flaggingService.evaluateSearchMetrics(businessId, saved);
            markSuccess(connection);
        } catch (Exception e) {
            markFailure(connection, "Search Console sync failed: " + e.getMessage());
            log.warn("Search Console sync failed for business {}: {}", businessId, e.toString());
        }
    }

    /** No-op when the business hasn't connected credentials yet, same as {@link
     * #syncSearchConsole}. Separate try/catch from Search Console's own sync so one Google API
     * (GA4) failing never blocks the other (Search Console) from recording its own success for the
     * same business in the same scheduler run. */
    @Transactional
    public void syncAnalytics(Long businessId) {
        SeoConnection connection = connectionRepository.findByBusinessId(businessId).orElse(null);
        if (connection == null) return;

        try {
            GoogleAnalyticsClient client = new GoogleAnalyticsClient(connectionService.decryptedServiceAccountJson(connection));
            String propertyId = connection.getGa4PropertyId();
            LocalDate endDate = LocalDate.now(ZoneOffset.UTC);
            LocalDate startDate = endDate.minusDays(ANALYTICS_WINDOW_DAYS - 1);

            Map<LocalDate, GoogleAnalyticsClient.DailyTotals> totalsByDate =
                    client.dailyTotals(propertyId, startDate, endDate);
            for (Map.Entry<LocalDate, GoogleAnalyticsClient.DailyTotals> entry : totalsByDate.entrySet()) {
                LocalDate date = entry.getKey();
                GoogleAnalyticsClient.DailyTotals totals = entry.getValue();
                SeoAnalyticsSnapshot snapshot = analyticsSnapshotRepository
                        .findByBusinessIdAndDate(businessId, date)
                        .orElseGet(SeoAnalyticsSnapshot::new);
                snapshot.setBusinessId(businessId);
                snapshot.setDate(date);
                snapshot.setTotalUsers((int) totals.totalUsers());
                snapshot.setNewUsers((int) totals.newUsers());
                snapshot.setOrganicSessions((int) totals.organicSessions());
                analyticsSnapshotRepository.save(snapshot);
            }
            markSuccess(connection);
        } catch (Exception e) {
            markFailure(connection, "GA4 analytics sync failed: " + e.getMessage());
            log.warn("GA4 analytics sync failed for business {}: {}", businessId, e.toString());
        }
    }

    /** Homepage URL is derived from the business's own Search Console site (the same one {@link
     * #syncSearchConsole} queries), not {@code Business#getPublicDomain()} — found live 2026-09-01
     * that {@code public_domain} is the booking-app subdomain used to resolve which business a
     * landing-page request belongs to (e.g. {@code mani.akluxnails.com}), a completely different
     * site from the one this business's {@code seo_connection} actually has Search Console
     * configured for ({@code akluxnails.com}) — checking the wrong domain's Core Web Vitals is
     * worse than not checking at all. Homepage-only is still the deliberate v1 scope (design.md
     * Open Question 2); no-op if the business has no connection. */
    @Transactional
    public void syncPageSpeed(Long businessId) {
        SeoConnection connection = connectionRepository.findByBusinessId(businessId).orElse(null);
        if (connection == null) return;

        String homepageUrl;
        PageSpeedInsightsClient client;
        try {
            SearchConsoleClient searchConsoleClient = new SearchConsoleClient(connectionService.decryptedServiceAccountJson(connection));
            List<SearchConsoleClient.Site> sites = searchConsoleClient.sites();
            if (sites.isEmpty()) {
                throw new IllegalStateException("Service account has no visible Search Console sites");
            }
            homepageUrl = homepageUrlFromSiteUrl(sites.get(0).siteUrl());
            client = new PageSpeedInsightsClient(connectionService.decryptedPagespeedApiKey(connection));
        } catch (Exception e) {
            markFailure(connection, "PageSpeed sync failed: " + e.getMessage());
            log.warn("PageSpeed sync failed for business {}: {}", businessId, e.toString());
            return;
        }

        // Each strategy is attempted and persisted independently — mobile and desktop are
        // genuinely separate Lighthouse runs against Google's infrastructure, and one of them
        // hitting a transient error (NO_FCP, "Something went wrong", etc. — see design.md Risks
        // and PageSpeedInsightsClient's own doc comment) must never prevent the other, already-
        // working strategy's real result from being saved in the same sync.
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<String> errors = new ArrayList<>();
        for (SeoPageSnapshot.Strategy strategy : SeoPageSnapshot.Strategy.values()) {
            try {
                PageSpeedInsightsClient.Result result = client.check(homepageUrl, strategy);
                SeoPageSnapshot snapshot = pageSnapshotRepository
                        .findByBusinessIdAndDateAndUrlAndStrategy(businessId, today, homepageUrl, strategy)
                        .orElseGet(SeoPageSnapshot::new);
                snapshot.setBusinessId(businessId);
                snapshot.setDate(today);
                snapshot.setUrl(homepageUrl);
                snapshot.setStrategy(strategy);
                snapshot.setPerformanceScore(result.performanceScore());
                snapshot.setLcpMs(result.lcpMs());
                snapshot.setCls(result.cls());
                snapshot.setFcpMs(result.fcpMs());
                snapshot.setTbtMs(result.tbtMs());
                flaggingService.evaluatePageSnapshot(pageSnapshotRepository.save(snapshot));
            } catch (Exception e) {
                errors.add(strategy + ": " + e.getMessage());
                log.warn("PageSpeed sync failed for business {}, strategy {}: {}", businessId, strategy, e.toString());
            }
        }

        if (errors.isEmpty()) {
            markSuccess(connection);
        } else {
            markFailure(connection, "PageSpeed sync failed for " + errors.size() + "/2 strategies: "
                    + String.join("; ", errors));
        }
    }

    /** Runs PageSpeed Insights against every active competitor's homepage — same weekly cadence as
     * {@link #syncPageSpeed} (called right after it from the same scheduler run), reusing the
     * business's own PageSpeed API key since PSI scores any public URL regardless of whose site it
     * is (seo-intelligence-advisor Phase 7, design.md D9 — the zero-cost redesign). No-op if the
     * business has no connection or no active competitors. Deliberately does not touch {@code
     * seo_connection}'s own {@code lastSyncAt}/{@code lastSyncError} — a competitor's site being
     * unreachable is a different, less critical signal than our own site's sync health, so mixing
     * the two into one status field would be confusing. */
    @Transactional
    public void syncCompetitorPageSpeed(Long businessId) {
        SeoConnection connection = connectionRepository.findByBusinessId(businessId).orElse(null);
        if (connection == null) return;
        List<SeoCompetitor> activeCompetitors = competitorRepository.findByBusinessIdAndActiveTrueOrderByCreatedAtAsc(businessId);
        if (activeCompetitors.isEmpty()) return;

        PageSpeedInsightsClient client;
        try {
            client = new PageSpeedInsightsClient(connectionService.decryptedPagespeedApiKey(connection));
        } catch (Exception e) {
            log.warn("Competitor PageSpeed sync failed for business {}: {}", businessId, e.toString());
            return;
        }

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (SeoCompetitor competitor : activeCompetitors) {
            for (SeoPageSnapshot.Strategy strategy : SeoPageSnapshot.Strategy.values()) {
                try {
                    PageSpeedInsightsClient.Result result = client.check(competitor.getWebsite(), strategy);
                    SeoCompetitorPageSnapshot snapshot = competitorPageSnapshotRepository
                            .findByCompetitorIdAndDateAndStrategy(competitor.getId(), today, strategy)
                            .orElseGet(SeoCompetitorPageSnapshot::new);
                    snapshot.setCompetitorId(competitor.getId());
                    snapshot.setDate(today);
                    snapshot.setStrategy(strategy);
                    snapshot.setPerformanceScore(result.performanceScore());
                    snapshot.setLcpMs(result.lcpMs());
                    snapshot.setCls(result.cls());
                    snapshot.setFcpMs(result.fcpMs());
                    snapshot.setTbtMs(result.tbtMs());
                    competitorPageSnapshotRepository.save(snapshot);
                } catch (Exception e) {
                    log.warn("Competitor PageSpeed sync failed for business {}, competitor {}, strategy {}: {}",
                            businessId, competitor.getId(), strategy, e.toString());
                }
            }
        }
    }

    /** A Search Console site URL is either a Domain property ({@code sc-domain:example.com}, no
     * scheme — AK.LUX.NAILS' actual shape, confirmed live) or a URL-prefix property (already a real
     * URL like {@code https://example.com/}) — never assume which one a given business has. */
    private static String homepageUrlFromSiteUrl(String siteUrl) {
        return siteUrl.startsWith("sc-domain:")
                ? "https://" + siteUrl.substring("sc-domain:".length()) + "/"
                : siteUrl;
    }

    private void markSuccess(SeoConnection connection) {
        connection.setLastSyncAt(Instant.now());
        connection.setLastSyncError(null);
        connectionRepository.save(connection);
    }

    private void markFailure(SeoConnection connection, String message) {
        connection.setLastSyncError(message);
        connectionRepository.save(connection);
    }
}
