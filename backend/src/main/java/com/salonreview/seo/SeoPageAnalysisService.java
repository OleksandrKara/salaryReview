package com.salonreview.seo;

import com.salonreview.domain.SeoSearchMetricsSnapshot;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Page-level counterpart to {@link SeoChangeDetectionService} — same pure, repository-free,
 * before/after-half-window shape (via {@link SeoWindowSplit}), just keyed by {@code page} instead
 * of {@code query} (seo-intelligence-advisor design.md D5/tasks.md Phase 3). Also owns query→page
 * cannibalization detection: {@code seo_search_metrics_snapshot} already carries both dimensions
 * per row, so this is a presentation/aggregation gap, not a data gap — no new table, no new sync.
 */
public class SeoPageAnalysisService {

    // Same significance floor as SeoChangeDetectionService's query-level movers — a page's
    // clicks/impressions need to move by at least this fraction to count as a real win/loss, not
    // day-to-day noise.
    public static final BigDecimal SIGNIFICANT_CHANGE_RATIO = BigDecimal.valueOf(0.20);
    public static final int SIGNIFICANT_MOVE_MIN_IMPRESSIONS = SeoChangeDetectionService.SIGNIFICANT_MOVE_MIN_IMPRESSIONS;
    public static final int MAX_RESULTS = SeoChangeDetectionService.MAX_RESULTS;

    // A page counts as "underperforming" when it has real demand (impressions) but a weak
    // position — i.e. the opportunity is in ranking better, not just improving CTR (that's the
    // existing CTR_OPPORTUNITY heuristic's own job, evaluated per-query not per-page).
    public static final int UNDERPERFORMING_MIN_IMPRESSIONS = SeoChangeDetectionService.STRIKING_DISTANCE_MIN_IMPRESSIONS;
    public static final BigDecimal UNDERPERFORMING_MIN_POSITION = BigDecimal.valueOf(10);

    // Content opportunity band per the proposal: pages ranking 5-20 with meaningful impressions
    // are a more achievable rewrite/expand target than a page buried past 20.
    public static final BigDecimal CONTENT_OPPORTUNITY_MIN_POSITION = BigDecimal.valueOf(5);
    public static final BigDecimal CONTENT_OPPORTUNITY_MAX_POSITION = BigDecimal.valueOf(20);

    // A query is flagged as (potentially) cannibalized when more than one page each receive at
    // least this share of that query's total impressions in the window — a page with a token 2%
    // sliver of impressions isn't really "competing," it's noise (e.g. an old URL Google hasn't
    // fully dropped from its index yet).
    public static final BigDecimal CANNIBALIZATION_MIN_SHARE = BigDecimal.valueOf(0.15);
    public static final int CANNIBALIZATION_MIN_TOTAL_IMPRESSIONS = SeoChangeDetectionService.SIGNIFICANT_MOVE_MIN_IMPRESSIONS;

    public record PageChange(String page, long previousImpressions, long currentImpressions,
            long previousClicks, long currentClicks, BigDecimal changeRatio) {
    }

    public record PageOpportunity(String page, BigDecimal currentPosition, long currentImpressions) {
    }

    /** {@code page} is {@code null}-free by construction — {@link
     * SeoSearchMetricsSnapshot#getPage()} rows with no page dimension are excluded from all
     * page-level analysis here, since "which page competes for this query" is meaningless without
     * one. */
    public record CannibalizedQuery(String query, List<PageShare> pages) {
    }

    public record PageShare(String page, long impressions, BigDecimal share, BigDecimal position) {
    }

    public List<PageChange> winningPages(List<SeoSearchMetricsSnapshot> rows, LocalDate start, LocalDate end) {
        return significantPageMovers(rows, start, end, true);
    }

    public List<PageChange> losingPages(List<SeoSearchMetricsSnapshot> rows, LocalDate start, LocalDate end) {
        return significantPageMovers(rows, start, end, false);
    }

    private List<PageChange> significantPageMovers(List<SeoSearchMetricsSnapshot> rows, LocalDate start,
            LocalDate end, boolean winning) {
        List<PageChange> result = new ArrayList<>();
        for (Map.Entry<String, SeoWindowSplit.HalfWindowPair> entry : byPage(rows, start, end).entrySet()) {
            SeoMetricsAggregate previous = entry.getValue().previous();
            SeoMetricsAggregate current = entry.getValue().current();
            if (previous == null || current == null) continue;
            if (previous.impressions() < SIGNIFICANT_MOVE_MIN_IMPRESSIONS) continue;

            BigDecimal ratio = BigDecimal.valueOf(current.impressions() - previous.impressions())
                    .divide(BigDecimal.valueOf(previous.impressions()), 4, java.math.RoundingMode.HALF_UP);
            boolean isSignificant = winning
                    ? ratio.compareTo(SIGNIFICANT_CHANGE_RATIO) >= 0
                    : ratio.negate().compareTo(SIGNIFICANT_CHANGE_RATIO) >= 0;
            if (!isSignificant) continue;

            result.add(new PageChange(entry.getKey(), previous.impressions(), current.impressions(),
                    previous.clicks(), current.clicks(), ratio));
        }
        result.sort(winning
                ? Comparator.comparing(PageChange::changeRatio).reversed()
                : Comparator.comparing(PageChange::changeRatio));
        return result.size() > MAX_RESULTS ? result.subList(0, MAX_RESULTS) : result;
    }

    /** Pages with real demand but a weak ranking — the opportunity is in ranking better, in
     * contrast to {@link SeoIssueFlaggingService}'s per-query CTR heuristic (weak click-through
     * despite an already-decent ranking). */
    public List<PageOpportunity> underperformingPages(List<SeoSearchMetricsSnapshot> rows, LocalDate start, LocalDate end) {
        List<PageOpportunity> result = new ArrayList<>();
        for (Map.Entry<String, SeoWindowSplit.HalfWindowPair> entry : byPage(rows, start, end).entrySet()) {
            SeoMetricsAggregate current = entry.getValue().current();
            if (current == null) continue;
            if (current.impressions() >= UNDERPERFORMING_MIN_IMPRESSIONS
                    && current.position().compareTo(UNDERPERFORMING_MIN_POSITION) > 0) {
                result.add(new PageOpportunity(entry.getKey(), current.position(), current.impressions()));
            }
        }
        result.sort(Comparator.comparingLong(PageOpportunity::currentImpressions).reversed());
        return result.size() > MAX_RESULTS ? result.subList(0, MAX_RESULTS) : result;
    }

    /** Pages ranking in the 5-20 "content opportunity" band per the proposal — a more achievable
     * rewrite/expand target than a page buried past position 20. */
    public List<PageOpportunity> contentOpportunities(List<SeoSearchMetricsSnapshot> rows, LocalDate start, LocalDate end) {
        List<PageOpportunity> result = new ArrayList<>();
        for (Map.Entry<String, SeoWindowSplit.HalfWindowPair> entry : byPage(rows, start, end).entrySet()) {
            SeoMetricsAggregate current = entry.getValue().current();
            if (current == null) continue;
            if (current.impressions() >= UNDERPERFORMING_MIN_IMPRESSIONS
                    && current.position().compareTo(CONTENT_OPPORTUNITY_MIN_POSITION) >= 0
                    && current.position().compareTo(CONTENT_OPPORTUNITY_MAX_POSITION) <= 0) {
                result.add(new PageOpportunity(entry.getKey(), current.position(), current.impressions()));
            }
        }
        result.sort(Comparator.comparingLong(PageOpportunity::currentImpressions).reversed());
        return result.size() > MAX_RESULTS ? result.subList(0, MAX_RESULTS) : result;
    }

    /** Flags queries where more than one page holds a meaningful share of that query's total
     * impressions in the window — labeled a "potential optimization opportunity" by the caller
     * (frontend), never asserted as a confirmed problem (design.md D5's own explicit instruction:
     * present it as a possibility, since a business can legitimately have two pages both
     * reasonably ranking for a broad query). */
    public List<CannibalizedQuery> cannibalizedQueries(List<SeoSearchMetricsSnapshot> rows) {
        Map<String, Map<String, List<SeoSearchMetricsSnapshot>>> byQueryThenPage = new LinkedHashMap<>();
        for (SeoSearchMetricsSnapshot row : rows) {
            if (row.getPage() == null) continue;
            byQueryThenPage
                    .computeIfAbsent(row.getQuery(), q -> new LinkedHashMap<>())
                    .computeIfAbsent(row.getPage(), p -> new ArrayList<>())
                    .add(row);
        }

        List<CannibalizedQuery> result = new ArrayList<>();
        for (Map.Entry<String, Map<String, List<SeoSearchMetricsSnapshot>>> queryEntry : byQueryThenPage.entrySet()) {
            Map<String, List<SeoSearchMetricsSnapshot>> byPage = queryEntry.getValue();
            if (byPage.size() < 2) continue;

            long totalImpressions = byPage.values().stream()
                    .flatMap(List::stream).mapToLong(SeoSearchMetricsSnapshot::getImpressions).sum();
            if (totalImpressions < CANNIBALIZATION_MIN_TOTAL_IMPRESSIONS) continue;

            List<PageShare> shares = new ArrayList<>();
            for (Map.Entry<String, List<SeoSearchMetricsSnapshot>> pageEntry : byPage.entrySet()) {
                SeoMetricsAggregate agg = SeoMetricsAggregate.of(pageEntry.getValue());
                BigDecimal share = BigDecimal.valueOf(agg.impressions())
                        .divide(BigDecimal.valueOf(totalImpressions), 4, java.math.RoundingMode.HALF_UP);
                if (share.compareTo(CANNIBALIZATION_MIN_SHARE) >= 0) {
                    shares.add(new PageShare(pageEntry.getKey(), agg.impressions(), share, agg.position()));
                }
            }
            if (shares.size() < 2) continue;

            shares.sort(Comparator.comparingLong(PageShare::impressions).reversed());
            result.add(new CannibalizedQuery(queryEntry.getKey(), shares));
        }
        result.sort(Comparator.<CannibalizedQuery>comparingLong(
                cq -> cq.pages().stream().mapToLong(PageShare::impressions).sum()).reversed());
        return result.size() > MAX_RESULTS ? result.subList(0, MAX_RESULTS) : result;
    }

    private Map<String, SeoWindowSplit.HalfWindowPair> byPage(List<SeoSearchMetricsSnapshot> rows, LocalDate start,
            LocalDate end) {
        List<SeoSearchMetricsSnapshot> withPage = rows.stream().filter(r -> r.getPage() != null).toList();
        return SeoWindowSplit.byKey(withPage, start, end, SeoSearchMetricsSnapshot::getPage);
    }
}
