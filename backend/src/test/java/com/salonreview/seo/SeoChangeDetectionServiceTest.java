package com.salonreview.seo;

import com.salonreview.domain.SeoSearchMetricsSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SeoChangeDetectionServiceTest {

    private final SeoChangeDetectionService service = new SeoChangeDetectionService();
    private final LocalDate start = LocalDate.of(2026, 8, 1);
    private final LocalDate end = LocalDate.of(2026, 8, 28); // 28-day window, mid = Aug 14/15

    private SeoSearchMetricsSnapshot row(LocalDate date, String query, int impressions, BigDecimal position) {
        return SeoSearchMetricsSnapshot.builder()
                .businessId(1L).date(date).query(query).page("/")
                .clicks((int) Math.round(impressions * 0.05))
                .impressions(impressions).ctr(BigDecimal.valueOf(0.05)).position(position)
                .build();
    }

    @Test
    @DisplayName("gainers(): a query improving by >= 4 positions with enough earlier-half impressions is included")
    void gainersIncludesSignificantImprovement() {
        List<SeoSearchMetricsSnapshot> rows = List.of(
                row(start, "russian manicure san diego", 100, BigDecimal.valueOf(8)),
                row(end, "russian manicure san diego", 100, BigDecimal.valueOf(3)));

        List<SeoChangeDetectionService.QueryChange> gainers = service.gainers(rows, start, end);

        assertThat(gainers).hasSize(1);
        assertThat(gainers.get(0).query()).isEqualTo("russian manicure san diego");
        assertThat(gainers.get(0).positionDelta()).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("gainers(): a move smaller than the significance threshold is excluded (no #10 -> #9 noise)")
    void gainersExcludesInsignificantMove() {
        List<SeoSearchMetricsSnapshot> rows = List.of(
                row(start, "gel nail extensions san diego", 100, BigDecimal.valueOf(10)),
                row(end, "gel nail extensions san diego", 100, BigDecimal.valueOf(9)));

        assertThat(service.gainers(rows, start, end)).isEmpty();
    }

    @Test
    @DisplayName("gainers(): a big move on too little earlier-half data is excluded as noise")
    void gainersExcludesLowImpressionMove() {
        List<SeoSearchMetricsSnapshot> rows = List.of(
                row(start, "obscure query", 2, BigDecimal.valueOf(40)),
                row(end, "obscure query", 2, BigDecimal.valueOf(4)));

        assertThat(service.gainers(rows, start, end)).isEmpty();
    }

    @Test
    @DisplayName("losers(): a query declining by >= 4 positions is included, sorted worst-first")
    void losersIncludesSignificantDecline() {
        List<SeoSearchMetricsSnapshot> rows = List.of(
                row(start, "query a", 100, BigDecimal.valueOf(3)),
                row(end, "query a", 100, BigDecimal.valueOf(9)),
                row(start, "query b", 100, BigDecimal.valueOf(2)),
                row(end, "query b", 100, BigDecimal.valueOf(20)));

        List<SeoChangeDetectionService.QueryChange> losers = service.losers(rows, start, end);

        assertThat(losers).hasSize(2);
        assertThat(losers.get(0).query()).isEqualTo("query b"); // -18 is worse than -6
        assertThat(losers.get(0).positionDelta()).isEqualByComparingTo("-18");
        assertThat(losers.get(1).query()).isEqualTo("query a");
    }

    @Test
    @DisplayName("A query present in only one half (no prior or no current data) is excluded from gainers/losers")
    void onlyOneHalfPresentExcludedFromMovers() {
        List<SeoSearchMetricsSnapshot> rows = List.of(row(end, "brand new query", 100, BigDecimal.valueOf(5)));

        assertThat(service.gainers(rows, start, end)).isEmpty();
        assertThat(service.losers(rows, start, end)).isEmpty();
    }

    @Test
    @DisplayName("opportunities(): a query ranking in the striking-distance band with enough impressions is flagged")
    void opportunitiesFlagsStrikingDistance() {
        List<SeoSearchMetricsSnapshot> rows = List.of(row(end, "gel manicure san diego", 50, BigDecimal.valueOf(12)));

        List<SeoChangeDetectionService.Opportunity> opportunities = service.opportunities(rows, start, end);

        assertThat(opportunities).hasSize(1);
        assertThat(opportunities.get(0).reason()).isEqualTo(SeoChangeDetectionService.OpportunityReason.STRIKING_DISTANCE);
    }

    @Test
    @DisplayName("opportunities(): a query ranking well outside the striking-distance band is not flagged for it")
    void opportunitiesExcludesOutOfBandPosition() {
        List<SeoSearchMetricsSnapshot> rows = List.of(
                row(end, "already winning", 50, BigDecimal.valueOf(2)),
                row(end, "too far out", 50, BigDecimal.valueOf(45)));

        assertThat(service.opportunities(rows, start, end)).isEmpty();
    }

    @Test
    @DisplayName("opportunities(): high impressions with very low CTR is flagged even outside striking distance")
    void opportunitiesFlagsHighImpressionsLowCtr() {
        SeoSearchMetricsSnapshot lowCtrRow = SeoSearchMetricsSnapshot.builder()
                .businessId(1L).date(end).query("weak title tag query").page("/blog/x")
                .clicks(2).impressions(200).ctr(BigDecimal.valueOf(0.01)).position(BigDecimal.valueOf(2))
                .build();

        List<SeoChangeDetectionService.Opportunity> opportunities = service.opportunities(List.of(lowCtrRow), start, end);

        assertThat(opportunities).hasSize(1);
        assertThat(opportunities.get(0).reason())
                .isEqualTo(SeoChangeDetectionService.OpportunityReason.HIGH_IMPRESSIONS_LOW_CTR);
    }

    @Test
    @DisplayName("opportunities(): impressions growing at least 1.5x between halves is flagged when nothing else applies")
    void opportunitiesFlagsGrowingImpressions() {
        List<SeoSearchMetricsSnapshot> rows = List.of(
                row(start, "growing query", 40, BigDecimal.valueOf(2)),
                row(end, "growing query", 100, BigDecimal.valueOf(2)));

        List<SeoChangeDetectionService.Opportunity> opportunities = service.opportunities(rows, start, end);

        assertThat(opportunities).hasSize(1);
        assertThat(opportunities.get(0).reason())
                .isEqualTo(SeoChangeDetectionService.OpportunityReason.GROWING_IMPRESSIONS);
    }

    @Test
    @DisplayName("gainers()/losers()/opportunities() cap results at MAX_RESULTS, most-significant first")
    void resultsAreCappedAtMaxResults() {
        List<SeoSearchMetricsSnapshot> rows = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            rows.add(row(start, "query " + i, 100, BigDecimal.valueOf(10 + i)));
            rows.add(row(end, "query " + i, 100, BigDecimal.valueOf(10 + i - (i + 1))));
        }

        List<SeoChangeDetectionService.QueryChange> gainers = service.gainers(rows, start, end);

        assertThat(gainers).hasSize(SeoChangeDetectionService.MAX_RESULTS);
        // Most-significant (largest positionDelta) first.
        for (int i = 1; i < gainers.size(); i++) {
            assertThat(gainers.get(i - 1).positionDelta()).isGreaterThanOrEqualTo(gainers.get(i).positionDelta());
        }
    }
}
