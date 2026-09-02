package com.salonreview.seo;

import com.salonreview.domain.SeoAnalyticsSnapshot;
import com.salonreview.domain.SeoConnection;
import com.salonreview.domain.SeoPageSnapshot;
import com.salonreview.domain.SeoSearchMetricsSnapshot;
import com.salonreview.domain.SeoTechnicalIssue;
import com.salonreview.domain.SeoTrackedQuery;
import com.salonreview.repo.SeoAnalyticsSnapshotRepository;
import com.salonreview.repo.SeoConnectionRepository;
import com.salonreview.repo.SeoPageSnapshotRepository;
import com.salonreview.repo.SeoSearchMetricsSnapshotRepository;
import com.salonreview.repo.SeoTechnicalIssueRepository;
import com.salonreview.repo.SeoTrackedQueryRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the read model for {@code GET /api/owner/marketing/seo/overview} — pure aggregation over
 * the tables Phase 1-5 already populate, no external API calls (those only happen in {@link
 * SeoSyncService}, on a schedule or via the manual-sync endpoint).
 */
@Service
public class SeoDashboardService {

    // How many auto-suggested queries to fall back to when the owner hasn't pinned any tracked
    // query yet — mirrors topQueries()'s own top-20 cap in spirit, but smaller: this list is meant
    // to be scanned at a glance for "did our main terms move," not browsed like the full table.
    private static final int AUTO_SUGGESTED_QUERY_LIMIT = 10;

    private final SeoConnectionRepository connectionRepository;
    private final SeoSearchMetricsSnapshotRepository searchMetricsRepository;
    private final SeoPageSnapshotRepository pageSnapshotRepository;
    private final SeoAnalyticsSnapshotRepository analyticsSnapshotRepository;
    private final SeoTechnicalIssueRepository issueRepository;
    private final SeoTrackedQueryRepository trackedQueryRepository;

    public SeoDashboardService(SeoConnectionRepository connectionRepository,
            SeoSearchMetricsSnapshotRepository searchMetricsRepository,
            SeoPageSnapshotRepository pageSnapshotRepository,
            SeoAnalyticsSnapshotRepository analyticsSnapshotRepository,
            SeoTechnicalIssueRepository issueRepository, SeoTrackedQueryRepository trackedQueryRepository) {
        this.connectionRepository = connectionRepository;
        this.searchMetricsRepository = searchMetricsRepository;
        this.pageSnapshotRepository = pageSnapshotRepository;
        this.analyticsSnapshotRepository = analyticsSnapshotRepository;
        this.issueRepository = issueRepository;
        this.trackedQueryRepository = trackedQueryRepository;
    }

    public record TrendPoint(LocalDate date, long clicks, long impressions, BigDecimal ctr, BigDecimal position) {}

    public record KeywordRow(String query, long clicks, long impressions, BigDecimal ctr, BigDecimal position) {}

    public record AnalyticsPoint(LocalDate date, long totalUsers, long newUsers, long organicSessions) {}

    /** {@code previousPosition}/{@code currentPosition} are {@code null} when the query had zero
     * impressions in that half of the window (nothing to average) — the frontend shows "—", not a
     * misleading zero. {@code positionDelta} is {@code previousPosition - currentPosition}: positive
     * means the query moved to a numerically lower (better) position, i.e. improved — same
     * "positive is good" sign convention as the revenue MoM delta elsewhere in this app. {@code
     * autoSuggested} is true when this query came from the impressions-ranked fallback rather than
     * the owner's own {@code seo_tracked_query} list (hybrid approach — see design discussion). */
    public record TrackedQueryRow(String query, BigDecimal previousPosition, BigDecimal currentPosition,
                                   BigDecimal positionDelta, long currentImpressions, boolean autoSuggested) {}

    public record CoreWebVitals(LocalDate date, Integer performanceScore, Integer lcpMs, BigDecimal cls,
                                 Integer fcpMs, Integer tbtMs) {}

    public record IssueRow(String issueType, String severity, String detail, String url, String query) {}

    public record Overview(boolean connected, java.time.Instant lastSyncAt, String lastSyncError,
                            List<TrendPoint> trend, List<AnalyticsPoint> analyticsTrend,
                            List<KeywordRow> topQueries, List<TrackedQueryRow> trackedQueries,
                            CoreWebVitals mobile, CoreWebVitals desktop, List<IssueRow> activeIssues) {}

    /** {@code null} means "no seo_connection row yet" — the caller (controller) decides how to
     * render that (empty-state card, per design.md D7), not this service. */
    public Overview overview(Long businessId, int days) {
        SeoConnection connection = connectionRepository.findByBusinessId(businessId).orElse(null);
        if (connection == null) {
            return new Overview(false, null, null, List.of(), List.of(), List.of(), List.of(), null, null, List.of());
        }

        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(days);
        List<SeoSearchMetricsSnapshot> rows =
                searchMetricsRepository.findByBusinessIdAndDateBetweenOrderByDateAsc(businessId, start, end);
        List<SeoAnalyticsSnapshot> analyticsRows =
                analyticsSnapshotRepository.findByBusinessIdAndDateBetweenOrderByDateAsc(businessId, start, end);

        return new Overview(true, connection.getLastSyncAt(), connection.getLastSyncError(),
                trend(rows), analyticsTrend(analyticsRows), topQueries(rows),
                trackedQueries(businessId, rows, start, end),
                latestVitals(businessId, SeoPageSnapshot.Strategy.MOBILE),
                latestVitals(businessId, SeoPageSnapshot.Strategy.DESKTOP),
                activeIssues(businessId));
    }

    /** No-op (not an error) if this exact query is already pinned — {@code
     * seo_tracked_query(business_id, query)}'s unique constraint means a duplicate add is
     * meaningless, not a conflict worth surfacing to the owner. Blank/whitespace-only input is
     * rejected by the controller before this is ever called. */
    public void addTrackedQuery(Long businessId, String query) {
        if (trackedQueryRepository.findByBusinessIdAndQuery(businessId, query).isPresent()) return;
        trackedQueryRepository.save(SeoTrackedQuery.builder().businessId(businessId).query(query).build());
    }

    /** No-op if the query wasn't tracked — removing something already absent isn't an error. */
    public void removeTrackedQuery(Long businessId, String query) {
        trackedQueryRepository.findByBusinessIdAndQuery(businessId, query)
                .ifPresent(trackedQueryRepository::delete);
    }

    private List<AnalyticsPoint> analyticsTrend(List<SeoAnalyticsSnapshot> rows) {
        return rows.stream()
                .map(r -> new AnalyticsPoint(r.getDate(), r.getTotalUsers(), r.getNewUsers(), r.getOrganicSessions()))
                .toList();
    }

    /** Splits the window in half (earlier half vs. later half) and compares each tracked query's
     * impressions-weighted average position between the two — a simple before/after within the
     * same requested window, not a separate API call or a second stored baseline. The tracked-query
     * list itself is hybrid: the owner's own {@code seo_tracked_query} rows if any exist, otherwise
     * the top-impression queries in this window stand in as a reasonable default (flagged {@code
     * autoSuggested}) so the section is never empty on a business that hasn't curated one yet. */
    private List<TrackedQueryRow> trackedQueries(Long businessId, List<SeoSearchMetricsSnapshot> rows,
            LocalDate start, LocalDate end) {
        List<String> pinned = trackedQueryRepository.findByBusinessIdOrderByCreatedAtAsc(businessId).stream()
                .map(SeoTrackedQuery::getQuery)
                .toList();
        boolean autoSuggested = pinned.isEmpty();
        Set<String> queries = new LinkedHashSet<>(autoSuggested ? topQueriesByImpressions(rows) : pinned);

        long midEpochDay = start.toEpochDay() + (end.toEpochDay() - start.toEpochDay()) / 2;
        LocalDate mid = LocalDate.ofEpochDay(midEpochDay);

        Map<String, List<SeoSearchMetricsSnapshot>> byQuery = new LinkedHashMap<>();
        for (SeoSearchMetricsSnapshot row : rows) {
            byQuery.computeIfAbsent(row.getQuery(), q -> new ArrayList<>()).add(row);
        }

        List<TrackedQueryRow> result = new ArrayList<>();
        for (String query : queries) {
            List<SeoSearchMetricsSnapshot> queryRows = byQuery.getOrDefault(query, List.of());
            List<SeoSearchMetricsSnapshot> previousHalf = queryRows.stream().filter(r -> r.getDate().isBefore(mid)).toList();
            List<SeoSearchMetricsSnapshot> currentHalf = queryRows.stream().filter(r -> !r.getDate().isBefore(mid)).toList();

            TrendPoint previousAgg = previousHalf.isEmpty() ? null : aggregate(null, previousHalf);
            TrendPoint currentAgg = currentHalf.isEmpty() ? null : aggregate(null, currentHalf);
            BigDecimal previousPosition = previousAgg == null ? null : previousAgg.position();
            BigDecimal currentPosition = currentAgg == null ? null : currentAgg.position();
            BigDecimal delta = (previousPosition == null || currentPosition == null)
                    ? null : previousPosition.subtract(currentPosition);
            long currentImpressions = currentAgg == null ? 0 : currentAgg.impressions();

            result.add(new TrackedQueryRow(query, previousPosition, currentPosition, delta, currentImpressions, autoSuggested));
        }
        return result;
    }

    private List<String> topQueriesByImpressions(List<SeoSearchMetricsSnapshot> rows) {
        Map<String, Long> impressionsByQuery = new LinkedHashMap<>();
        for (SeoSearchMetricsSnapshot row : rows) {
            impressionsByQuery.merge(row.getQuery(), (long) row.getImpressions(), Long::sum);
        }
        return impressionsByQuery.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(AUTO_SUGGESTED_QUERY_LIMIT)
                .map(Map.Entry::getKey)
                .toList();
    }

    private List<TrendPoint> trend(List<SeoSearchMetricsSnapshot> rows) {
        Map<LocalDate, List<SeoSearchMetricsSnapshot>> byDate = new LinkedHashMap<>();
        for (SeoSearchMetricsSnapshot row : rows) {
            byDate.computeIfAbsent(row.getDate(), d -> new ArrayList<>()).add(row);
        }
        List<TrendPoint> points = new ArrayList<>();
        for (Map.Entry<LocalDate, List<SeoSearchMetricsSnapshot>> entry : byDate.entrySet()) {
            points.add(aggregate(entry.getKey(), entry.getValue()));
        }
        points.sort(Comparator.comparing(TrendPoint::date));
        return points;
    }

    private List<KeywordRow> topQueries(List<SeoSearchMetricsSnapshot> rows) {
        Map<String, List<SeoSearchMetricsSnapshot>> byQuery = new LinkedHashMap<>();
        for (SeoSearchMetricsSnapshot row : rows) {
            byQuery.computeIfAbsent(row.getQuery(), q -> new ArrayList<>()).add(row);
        }
        List<KeywordRow> keywords = new ArrayList<>();
        for (Map.Entry<String, List<SeoSearchMetricsSnapshot>> entry : byQuery.entrySet()) {
            TrendPoint agg = aggregate(null, entry.getValue());
            keywords.add(new KeywordRow(entry.getKey(), agg.clicks(), agg.impressions(), agg.ctr(), agg.position()));
        }
        keywords.sort(Comparator.comparingLong(KeywordRow::clicks).reversed());
        return keywords.size() > 20 ? keywords.subList(0, 20) : keywords;
    }

    private TrendPoint aggregate(LocalDate date, List<SeoSearchMetricsSnapshot> rows) {
        long clicks = rows.stream().mapToLong(SeoSearchMetricsSnapshot::getClicks).sum();
        long impressions = rows.stream().mapToLong(SeoSearchMetricsSnapshot::getImpressions).sum();
        BigDecimal ctr = impressions == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(clicks).divide(BigDecimal.valueOf(impressions), 6, RoundingMode.HALF_UP);
        // Position is weighted by impressions (Search Console's own convention — a query with 10x
        // the impressions should dominate the average, not count equally against a rare one).
        BigDecimal weightedPositionSum = rows.stream()
                .map(r -> r.getPosition().multiply(BigDecimal.valueOf(r.getImpressions())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal position = impressions == 0 ? BigDecimal.ZERO
                : weightedPositionSum.divide(BigDecimal.valueOf(impressions), 2, RoundingMode.HALF_UP);
        return new TrendPoint(date, clicks, impressions, ctr, position);
    }

    private CoreWebVitals latestVitals(Long businessId, SeoPageSnapshot.Strategy strategy) {
        return pageSnapshotRepository.findFirstByBusinessIdAndStrategyOrderByDateDesc(businessId, strategy)
                .map(s -> new CoreWebVitals(s.getDate(), s.getPerformanceScore(), s.getLcpMs(), s.getCls(),
                        s.getFcpMs(), s.getTbtMs()))
                .orElse(null);
    }

    private List<IssueRow> activeIssues(Long businessId) {
        return issueRepository.findByBusinessIdAndResolvedAtIsNull(businessId).stream()
                .map(i -> new IssueRow(i.getIssueType().name(), i.getSeverity().name(), i.getDetail(),
                        i.getUrl(), i.getQuery()))
                .toList();
    }
}
