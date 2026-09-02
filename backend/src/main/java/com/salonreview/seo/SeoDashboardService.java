package com.salonreview.seo;

import com.salonreview.domain.SeoAnalyticsSnapshot;
import com.salonreview.domain.SeoConnection;
import com.salonreview.domain.SeoPageSnapshot;
import com.salonreview.domain.SeoSearchMetricsSnapshot;
import com.salonreview.domain.SeoTechnicalIssue;
import com.salonreview.domain.SeoTrackedKeyword;
import com.salonreview.domain.SeoTrackedQuery;
import com.salonreview.repo.SeoAnalyticsSnapshotRepository;
import com.salonreview.repo.SeoConnectionRepository;
import com.salonreview.repo.SeoPageSnapshotRepository;
import com.salonreview.repo.SeoSearchMetricsSnapshotRepository;
import com.salonreview.repo.SeoTechnicalIssueRepository;
import com.salonreview.repo.SeoTrackedKeywordRepository;
import com.salonreview.repo.SeoTrackedQueryRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private final SeoTrackedKeywordRepository trackedKeywordRepository;
    // Stateless, dependency-free (design.md D4) — constructed directly rather than injected, same
    // as any other plain value-object helper; no reason to make Spring manage a bean with nothing
    // to wire in.
    private final SeoChangeDetectionService changeDetectionService = new SeoChangeDetectionService();
    private final SeoPageAnalysisService pageAnalysisService = new SeoPageAnalysisService();

    public SeoDashboardService(SeoConnectionRepository connectionRepository,
            SeoSearchMetricsSnapshotRepository searchMetricsRepository,
            SeoPageSnapshotRepository pageSnapshotRepository,
            SeoAnalyticsSnapshotRepository analyticsSnapshotRepository,
            SeoTechnicalIssueRepository issueRepository, SeoTrackedQueryRepository trackedQueryRepository,
            SeoTrackedKeywordRepository trackedKeywordRepository) {
        this.connectionRepository = connectionRepository;
        this.searchMetricsRepository = searchMetricsRepository;
        this.pageSnapshotRepository = pageSnapshotRepository;
        this.analyticsSnapshotRepository = analyticsSnapshotRepository;
        this.issueRepository = issueRepository;
        this.trackedQueryRepository = trackedQueryRepository;
        this.trackedKeywordRepository = trackedKeywordRepository;
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

    /** {@code previous} is {@code null} when there's no data at all for the equivalent
     * immediately-prior period — the comparison is omitted entirely rather than shown against a
     * partial/misleading baseline (seo-intelligence-advisor design.md, "never fake a YoY/period
     * comparison without real history"). */
    public record PeriodComparison(TrendPoint current, TrendPoint previous) {}

    public record Overview(boolean connected, java.time.Instant lastSyncAt, String lastSyncError,
                            List<TrendPoint> trend, List<AnalyticsPoint> analyticsTrend,
                            List<KeywordRow> topQueries, List<TrackedQueryRow> trackedQueries,
                            CoreWebVitals mobile, CoreWebVitals desktop, List<IssueRow> activeIssues,
                            PeriodComparison last7Days, PeriodComparison last28Days, PeriodComparison yearOverYear,
                            List<SeoChangeDetectionService.QueryChange> gainers,
                            List<SeoChangeDetectionService.QueryChange> losers,
                            List<SeoChangeDetectionService.Opportunity> opportunities,
                            List<SeoPageAnalysisService.PageChange> winningPages,
                            List<SeoPageAnalysisService.PageChange> losingPages,
                            List<SeoPageAnalysisService.PageOpportunity> underperformingPages,
                            List<SeoPageAnalysisService.PageOpportunity> contentOpportunities,
                            List<SeoPageAnalysisService.CannibalizedQuery> cannibalizedQueries,
                            List<TrackedKeywordRow> trackedKeywords) {}

    /** {@code device} is {@code SeoTrackedKeyword.Device}'s name ("MOBILE"/"DESKTOP"). No rank
     * data on this row — {@code seo_rank_snapshot} (Phase 5) is keyed by the keyword's id once a
     * rank-tracking provider is connected; until then this is just the owner's curated list. */
    public record TrackedKeywordRow(Long id, String keyword, String targetUrl, String location, String device,
                                     boolean active) {}

    /** {@code null} means "no seo_connection row yet" — the caller (controller) decides how to
     * render that (empty-state card, per design.md D7), not this service. */
    public Overview overview(Long businessId, int days) {
        SeoConnection connection = connectionRepository.findByBusinessId(businessId).orElse(null);
        if (connection == null) {
            return new Overview(false, null, null, List.of(), List.of(), List.of(), List.of(), null, null, List.of(),
                    null, null, null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    trackedKeywords(businessId));
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
                activeIssues(businessId),
                last7DaysComparison(rows, businessId, end),
                last28DaysComparison(rows, businessId, start, days),
                yearOverYearComparison(rows, businessId, start, end),
                changeDetectionService.gainers(rows, start, end),
                changeDetectionService.losers(rows, start, end),
                changeDetectionService.opportunities(rows, start, end),
                pageAnalysisService.winningPages(rows, start, end),
                pageAnalysisService.losingPages(rows, start, end),
                pageAnalysisService.underperformingPages(rows, start, end),
                pageAnalysisService.contentOpportunities(rows, start, end),
                pageAnalysisService.cannibalizedQueries(rows),
                trackedKeywords(businessId));
    }

    /** Last 7 days vs. the 7 days immediately before that — both fully contained in the already-
     * fetched main window, so only the prior week needs its own repository call. */
    private PeriodComparison last7DaysComparison(List<SeoSearchMetricsSnapshot> mainWindowRows, Long businessId,
            LocalDate mainEnd) {
        LocalDate currentStart = mainEnd.minusDays(6);
        List<SeoSearchMetricsSnapshot> currentRows = filterByDate(mainWindowRows, currentStart, mainEnd);
        LocalDate priorEnd = currentStart.minusDays(1);
        LocalDate priorStart = priorEnd.minusDays(6);
        List<SeoSearchMetricsSnapshot> priorRows =
                searchMetricsRepository.findByBusinessIdAndDateBetweenOrderByDateAsc(businessId, priorStart, priorEnd);
        if (priorRows.isEmpty()) return null;
        return new PeriodComparison(aggregate(null, currentRows), aggregate(null, priorRows));
    }

    /** The full main window vs. the same-length window immediately before it — the prior window
     * falls entirely outside what {@link #overview} already fetched, so it needs its own call. */
    private PeriodComparison last28DaysComparison(List<SeoSearchMetricsSnapshot> mainWindowRows, Long businessId,
            LocalDate mainStart, int windowDays) {
        LocalDate priorEnd = mainStart.minusDays(1);
        LocalDate priorStart = priorEnd.minusDays(windowDays - 1);
        List<SeoSearchMetricsSnapshot> priorRows =
                searchMetricsRepository.findByBusinessIdAndDateBetweenOrderByDateAsc(businessId, priorStart, priorEnd);
        if (priorRows.isEmpty()) return null;
        return new PeriodComparison(aggregate(null, mainWindowRows), aggregate(null, priorRows));
    }

    /** The full main window vs. the same window one year prior — omitted entirely (returns {@code
     * null}) when that year-ago window has no data at all, rather than comparing against a
     * business that may not have existed/synced yet (design.md: no fabricated YoY baseline). */
    private PeriodComparison yearOverYearComparison(List<SeoSearchMetricsSnapshot> mainWindowRows, Long businessId,
            LocalDate mainStart, LocalDate mainEnd) {
        LocalDate priorStart = mainStart.minusYears(1);
        LocalDate priorEnd = mainEnd.minusYears(1);
        List<SeoSearchMetricsSnapshot> priorRows =
                searchMetricsRepository.findByBusinessIdAndDateBetweenOrderByDateAsc(businessId, priorStart, priorEnd);
        if (priorRows.isEmpty()) return null;
        return new PeriodComparison(aggregate(null, mainWindowRows), aggregate(null, priorRows));
    }

    private static List<SeoSearchMetricsSnapshot> filterByDate(List<SeoSearchMetricsSnapshot> rows, LocalDate start,
            LocalDate end) {
        return rows.stream().filter(r -> !r.getDate().isBefore(start) && !r.getDate().isAfter(end)).toList();
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

    private List<TrackedKeywordRow> trackedKeywords(Long businessId) {
        return trackedKeywordRepository.findByBusinessIdOrderByCreatedAtAsc(businessId).stream()
                .map(k -> new TrackedKeywordRow(k.getId(), k.getKeyword(), k.getTargetUrl(), k.getLocation(),
                        k.getDevice().name(), k.isActive()))
                .toList();
    }

    /** Owner-curated rank-tracking list (seo-intelligence-advisor Phase 4) — no rank checks happen
     * yet (Phase 5), this just builds the list so it isn't empty on day one of that phase.
     * Blank/whitespace-only keyword or location is rejected by the controller before this is ever
     * called. Adding an already-existing (keyword, location, device) combination is a no-op, same
     * "duplicate add is meaningless" convention as {@link #addTrackedQuery} — except an
     * inactive row for that exact combination is reactivated instead, since removing then
     * re-adding the same keyword is a real, expected owner action (not a fresh duplicate). */
    public void addTrackedKeyword(Long businessId, String keyword, String location, SeoTrackedKeyword.Device device,
            String targetUrl) {
        Optional<SeoTrackedKeyword> existing = trackedKeywordRepository.findByBusinessIdOrderByCreatedAtAsc(businessId)
                .stream()
                .filter(k -> k.getKeyword().equals(keyword) && k.getLocation().equals(location) && k.getDevice() == device)
                .findFirst();
        if (existing.isPresent()) {
            SeoTrackedKeyword keywordRow = existing.get();
            if (!keywordRow.isActive()) {
                keywordRow.setActive(true);
                keywordRow.setTargetUrl(targetUrl);
                trackedKeywordRepository.save(keywordRow);
            }
            return;
        }
        trackedKeywordRepository.save(SeoTrackedKeyword.builder()
                .businessId(businessId).keyword(keyword).location(location).device(device)
                .targetUrl(targetUrl).active(true).build());
    }

    /** No-op if the id doesn't exist or belongs to another business — same business-scoped-lookup
     * convention as every other caller-controlled-id repository access in this app. */
    public void removeTrackedKeyword(Long businessId, Long id) {
        trackedKeywordRepository.findByIdAndBusinessId(id, businessId).ifPresent(trackedKeywordRepository::delete);
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
        SeoMetricsAggregate agg = SeoMetricsAggregate.of(rows);
        return new TrendPoint(date, agg.clicks(), agg.impressions(), agg.ctr(), agg.position());
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
