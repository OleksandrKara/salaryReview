package com.salonreview.seo;

import com.salonreview.domain.Business;
import com.salonreview.domain.SeoConnection;
import com.salonreview.domain.SeoPageSnapshot;
import com.salonreview.domain.SeoSearchMetricsSnapshot;
import com.salonreview.repo.BusinessRepository;
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

    // Search Console's own data typically isn't final until 2-3 days after the fact — pulling
    // "today" would just get zero/partial rows every run.
    private static final int SEARCH_CONSOLE_LAG_DAYS = 3;
    private static final int SEARCH_CONSOLE_ROW_LIMIT = 200;

    private final SeoConnectionRepository connectionRepository;
    private final SeoConnectionService connectionService;
    private final BusinessRepository businessRepository;
    private final SeoSearchMetricsSnapshotRepository searchMetricsRepository;
    private final SeoPageSnapshotRepository pageSnapshotRepository;
    private final SeoIssueFlaggingService flaggingService;

    public SeoSyncService(SeoConnectionRepository connectionRepository, SeoConnectionService connectionService,
            BusinessRepository businessRepository, SeoSearchMetricsSnapshotRepository searchMetricsRepository,
            SeoPageSnapshotRepository pageSnapshotRepository, SeoIssueFlaggingService flaggingService) {
        this.connectionRepository = connectionRepository;
        this.connectionService = connectionService;
        this.businessRepository = businessRepository;
        this.searchMetricsRepository = searchMetricsRepository;
        this.pageSnapshotRepository = pageSnapshotRepository;
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
            LocalDate date = LocalDate.now(ZoneOffset.UTC).minusDays(SEARCH_CONSOLE_LAG_DAYS);

            List<SeoSearchMetricsSnapshot> saved = new ArrayList<>();
            for (SearchConsoleClient.QueryRow row : client.queryPerformance(siteUrl, date, SEARCH_CONSOLE_ROW_LIMIT)) {
                SeoSearchMetricsSnapshot snapshot = searchMetricsRepository
                        .findByBusinessIdAndDateAndQueryAndPage(businessId, date, row.query(), row.page())
                        .orElseGet(SeoSearchMetricsSnapshot::new);
                snapshot.setBusinessId(businessId);
                snapshot.setDate(date);
                snapshot.setQuery(row.query());
                snapshot.setPage(row.page());
                snapshot.setClicks((int) row.clicks());
                snapshot.setImpressions((int) row.impressions());
                snapshot.setCtr(row.ctr());
                snapshot.setPosition(row.position());
                saved.add(searchMetricsRepository.save(snapshot));
            }
            flaggingService.evaluateSearchMetrics(businessId, saved);
            markSuccess(connection);
        } catch (Exception e) {
            markFailure(connection, "Search Console sync failed: " + e.getMessage());
            log.warn("Search Console sync failed for business {}: {}", businessId, e.toString());
        }
    }

    /** Homepage URL is derived from {@link Business#getPublicDomain()} (already the field used to
     * resolve which business a public request belongs to — see {@code BusinessRepository}) rather
     * than adding a redundant URL column to {@code seo_connection}; homepage-only is the deliberate
     * v1 scope (design.md Open Question 2). No-op if the business has no connection or no
     * public_domain configured — the latter records a visible sync error rather than silently
     * skipping, since an owner enabling this feature would otherwise never learn why data never
     * appears. */
    @Transactional
    public void syncPageSpeed(Long businessId) {
        SeoConnection connection = connectionRepository.findByBusinessId(businessId).orElse(null);
        if (connection == null) return;

        Business business = businessRepository.findById(businessId).orElse(null);
        if (business == null || business.getPublicDomain() == null || business.getPublicDomain().isBlank()) {
            markFailure(connection, "PageSpeed sync skipped: business has no public_domain configured");
            return;
        }
        String homepageUrl = "https://" + business.getPublicDomain() + "/";

        try {
            PageSpeedInsightsClient client = new PageSpeedInsightsClient(connectionService.decryptedPagespeedApiKey(connection));
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            for (SeoPageSnapshot.Strategy strategy : SeoPageSnapshot.Strategy.values()) {
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
            }
            markSuccess(connection);
        } catch (Exception e) {
            markFailure(connection, "PageSpeed sync failed: " + e.getMessage());
            log.warn("PageSpeed sync failed for business {}: {}", businessId, e.toString());
        }
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
