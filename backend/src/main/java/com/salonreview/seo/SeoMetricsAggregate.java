package com.salonreview.seo;

import com.salonreview.domain.SeoSearchMetricsSnapshot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Clicks/impressions/CTR/impressions-weighted-average-position over a list of {@link
 * SeoSearchMetricsSnapshot} rows — one formula, reused by {@link SeoDashboardService}'s own
 * trend/keyword aggregation, {@link SeoChangeDetectionService}'s before/after query split, and
 * {@link SeoPageAnalysisService}'s page-level equivalent (seo-intelligence-advisor Phase 3).
 * Extracted once a third independent copy of this exact math was about to be written — two
 * near-identical blocks is fine, a third is where a shared formula starts paying for itself
 * (guarantees the weighted-position calculation can never quietly drift between call sites).
 */
public record SeoMetricsAggregate(long clicks, long impressions, BigDecimal ctr, BigDecimal position) {

    public static SeoMetricsAggregate of(List<SeoSearchMetricsSnapshot> rows) {
        long clicks = rows.stream().mapToLong(SeoSearchMetricsSnapshot::getClicks).sum();
        long impressions = rows.stream().mapToLong(SeoSearchMetricsSnapshot::getImpressions).sum();
        BigDecimal ctr = impressions == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(clicks).divide(BigDecimal.valueOf(impressions), 6, RoundingMode.HALF_UP);
        // Position is weighted by impressions (Search Console's own convention — a query/page with
        // 10x the impressions should dominate the average, not count equally against a rare one).
        BigDecimal weightedPositionSum = rows.stream()
                .map(r -> r.getPosition().multiply(BigDecimal.valueOf(r.getImpressions())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal position = impressions == 0 ? BigDecimal.ZERO
                : weightedPositionSum.divide(BigDecimal.valueOf(impressions), 2, RoundingMode.HALF_UP);
        return new SeoMetricsAggregate(clicks, impressions, ctr, position);
    }
}
