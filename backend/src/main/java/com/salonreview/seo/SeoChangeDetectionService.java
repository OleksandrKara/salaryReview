package com.salonreview.seo;

import com.salonreview.domain.SeoSearchMetricsSnapshot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure, repository-free aggregation over an already-fetched window of {@link
 * SeoSearchMetricsSnapshot} rows — same "no external calls, no DB access, easy to unit test" shape
 * as {@link SeoIssueFlaggingService} (seo-intelligence-advisor design.md D4). Splits the given
 * window in half (same before/after idea {@link SeoDashboardService#overview} already uses for
 * {@code trackedQueries}) and classifies each query's movement as a significant gain, a
 * significant loss, or an opportunity — never a raw, un-ranked list of every query that moved at
 * all, per the proposal's own explicit "don't show #10 -> #9" instruction.
 */
public class SeoChangeDetectionService {

    // A query must move at least this many positions between the earlier and later half of the
    // window to count as a "significant" gain/loss — small enough to catch a real, meaningful
    // improvement, large enough that ordinary day-to-day position noise (Search Console's
    // position is itself a variable, personalization/device-blended average) doesn't flood the
    // list. Not a Google-published number — an internal heuristic, same status as
    // CoreWebVitalsThresholds.CTR_OPPORTUNITY_MAX_RATIO.
    public static final BigDecimal SIGNIFICANT_POSITION_MOVE = BigDecimal.valueOf(4);

    // Minimum impressions in the *earlier* half before a position move is even considered — a
    // query with 2 impressions moving from position 40 to position 4 is noise, not a real signal.
    public static final int SIGNIFICANT_MOVE_MIN_IMPRESSIONS = 20;

    // How many gainers/losers/opportunities to return, most-significant first — mirrors
    // SeoDashboardService.topQueries()'s own top-20 cap in spirit (design.md D8's context-budget
    // constants reuse this same number for the AI Advisor later).
    public static final int MAX_RESULTS = 20;

    // Striking distance: a query ranking here is close enough that a real, achievable improvement
    // (better title/meta, one more internal link, a touch more content depth) plausibly moves it
    // onto page 1's upper half — position 1-3 is already "won," beyond 20 is a much longer play.
    public static final BigDecimal STRIKING_DISTANCE_MIN_POSITION = BigDecimal.valueOf(4);
    public static final BigDecimal STRIKING_DISTANCE_MAX_POSITION = BigDecimal.valueOf(20);

    // Lower than CoreWebVitalsThresholds.CTR_OPPORTUNITY_MIN_IMPRESSIONS (50) deliberately: that
    // constant gates the CTR *heuristic* (a page's title/meta likely needs work), which wants a
    // higher-confidence sample; striking-distance is a *ranking* opportunity, where even a more
    // modest, sustained level of demand is worth flagging so the owner sees it before it grows.
    public static final int STRIKING_DISTANCE_MIN_IMPRESSIONS = 20;

    // A query is a "growing impressions" opportunity when the later half's impressions are at
    // least this many times the earlier half's — same ratio-based shape as the CTR heuristic,
    // applied to growth instead of a shortfall.
    public static final BigDecimal GROWING_IMPRESSIONS_MIN_RATIO = BigDecimal.valueOf(1.5);

    public enum OpportunityReason {
        STRIKING_DISTANCE, HIGH_IMPRESSIONS_LOW_CTR, GROWING_IMPRESSIONS
    }

    /** {@code positionDelta = previousPosition - currentPosition}: positive means improved (moved
     * to a numerically lower/better position) — same sign convention as {@code
     * SeoDashboardService.TrackedQueryRow}. */
    public record QueryChange(String query, BigDecimal previousPosition, BigDecimal currentPosition,
            BigDecimal positionDelta, long previousImpressions, long currentImpressions,
            long previousClicks, long currentClicks) {
    }

    public record Opportunity(String query, BigDecimal currentPosition, long currentImpressions,
            BigDecimal currentCtr, OpportunityReason reason) {
    }

    private record HalfWindow(BigDecimal position, long impressions, long clicks, BigDecimal ctr) {
    }

    public List<QueryChange> gainers(List<SeoSearchMetricsSnapshot> rows, LocalDate start, LocalDate end) {
        return significantMovers(rows, start, end, true);
    }

    public List<QueryChange> losers(List<SeoSearchMetricsSnapshot> rows, LocalDate start, LocalDate end) {
        return significantMovers(rows, start, end, false);
    }

    private List<QueryChange> significantMovers(List<SeoSearchMetricsSnapshot> rows, LocalDate start, LocalDate end,
            boolean gains) {
        List<QueryChange> result = new ArrayList<>();
        for (Map.Entry<String, HalfWindow[]> entry : splitByQuery(rows, start, end).entrySet()) {
            HalfWindow previous = entry.getValue()[0];
            HalfWindow current = entry.getValue()[1];
            if (previous == null || current == null) continue;
            if (previous.impressions() < SIGNIFICANT_MOVE_MIN_IMPRESSIONS) continue;

            BigDecimal delta = previous.position().subtract(current.position());
            boolean isSignificant = gains
                    ? delta.compareTo(SIGNIFICANT_POSITION_MOVE) >= 0
                    : delta.negate().compareTo(SIGNIFICANT_POSITION_MOVE) >= 0;
            if (!isSignificant) continue;

            result.add(new QueryChange(entry.getKey(), previous.position(), current.position(), delta,
                    previous.impressions(), current.impressions(), previous.clicks(), current.clicks()));
        }
        result.sort(gains
                ? Comparator.comparing(QueryChange::positionDelta).reversed()
                : Comparator.comparing(QueryChange::positionDelta));
        return result.size() > MAX_RESULTS ? result.subList(0, MAX_RESULTS) : result;
    }

    public List<Opportunity> opportunities(List<SeoSearchMetricsSnapshot> rows, LocalDate start, LocalDate end) {
        List<Opportunity> result = new ArrayList<>();
        for (Map.Entry<String, HalfWindow[]> entry : splitByQuery(rows, start, end).entrySet()) {
            HalfWindow previous = entry.getValue()[0];
            HalfWindow current = entry.getValue()[1];
            if (current == null) continue;
            String query = entry.getKey();

            if (current.impressions() >= STRIKING_DISTANCE_MIN_IMPRESSIONS
                    && current.position().compareTo(STRIKING_DISTANCE_MIN_POSITION) >= 0
                    && current.position().compareTo(STRIKING_DISTANCE_MAX_POSITION) <= 0) {
                result.add(new Opportunity(query, current.position(), current.impressions(), current.ctr(),
                        OpportunityReason.STRIKING_DISTANCE));
                continue;
            }
            if (current.impressions() >= CoreWebVitalsThresholds.CTR_OPPORTUNITY_MIN_IMPRESSIONS
                    && current.ctr().compareTo(BigDecimal.valueOf(0.02)) <= 0) {
                result.add(new Opportunity(query, current.position(), current.impressions(), current.ctr(),
                        OpportunityReason.HIGH_IMPRESSIONS_LOW_CTR));
                continue;
            }
            if (previous != null && previous.impressions() >= SIGNIFICANT_MOVE_MIN_IMPRESSIONS) {
                BigDecimal ratio = BigDecimal.valueOf(current.impressions())
                        .divide(BigDecimal.valueOf(previous.impressions()), 4, RoundingMode.HALF_UP);
                if (ratio.compareTo(GROWING_IMPRESSIONS_MIN_RATIO) >= 0) {
                    result.add(new Opportunity(query, current.position(), current.impressions(), current.ctr(),
                            OpportunityReason.GROWING_IMPRESSIONS));
                }
            }
        }
        result.sort(Comparator.comparingLong(Opportunity::currentImpressions).reversed());
        return result.size() > MAX_RESULTS ? result.subList(0, MAX_RESULTS) : result;
    }

    /** Groups rows by query, then splits each query's rows into the earlier vs. later half of
     * [start, end] (same midpoint idea as {@code SeoDashboardService.trackedQueries}, duplicated
     * here rather than shared — this class is deliberately dependency-free/pure, see the class
     * doc comment). Each map value is a 2-element array: [previousHalf, currentHalf], either
     * possibly {@code null} when that half had no rows for this query at all. */
    private Map<String, HalfWindow[]> splitByQuery(List<SeoSearchMetricsSnapshot> rows, LocalDate start, LocalDate end) {
        long midEpochDay = start.toEpochDay() + (end.toEpochDay() - start.toEpochDay()) / 2;
        LocalDate mid = LocalDate.ofEpochDay(midEpochDay);

        Map<String, List<SeoSearchMetricsSnapshot>> previousByQuery = new LinkedHashMap<>();
        Map<String, List<SeoSearchMetricsSnapshot>> currentByQuery = new LinkedHashMap<>();
        for (SeoSearchMetricsSnapshot row : rows) {
            Map<String, List<SeoSearchMetricsSnapshot>> target = row.getDate().isBefore(mid) ? previousByQuery : currentByQuery;
            target.computeIfAbsent(row.getQuery(), q -> new ArrayList<>()).add(row);
        }

        Map<String, HalfWindow[]> result = new LinkedHashMap<>();
        for (String query : uniqueQueries(rows)) {
            HalfWindow previous = aggregateHalf(previousByQuery.get(query));
            HalfWindow current = aggregateHalf(currentByQuery.get(query));
            result.put(query, new HalfWindow[] {previous, current});
        }
        return result;
    }

    private static List<String> uniqueQueries(List<SeoSearchMetricsSnapshot> rows) {
        List<String> queries = new ArrayList<>();
        for (SeoSearchMetricsSnapshot row : rows) {
            if (!queries.contains(row.getQuery())) queries.add(row.getQuery());
        }
        return queries;
    }

    private HalfWindow aggregateHalf(List<SeoSearchMetricsSnapshot> rows) {
        if (rows == null || rows.isEmpty()) return null;
        long clicks = rows.stream().mapToLong(SeoSearchMetricsSnapshot::getClicks).sum();
        long impressions = rows.stream().mapToLong(SeoSearchMetricsSnapshot::getImpressions).sum();
        BigDecimal ctr = impressions == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(clicks).divide(BigDecimal.valueOf(impressions), 6, RoundingMode.HALF_UP);
        BigDecimal weightedPositionSum = rows.stream()
                .map(r -> r.getPosition().multiply(BigDecimal.valueOf(r.getImpressions())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal position = impressions == 0 ? BigDecimal.ZERO
                : weightedPositionSum.divide(BigDecimal.valueOf(impressions), 2, RoundingMode.HALF_UP);
        return new HalfWindow(position, impressions, clicks, ctr);
    }
}
