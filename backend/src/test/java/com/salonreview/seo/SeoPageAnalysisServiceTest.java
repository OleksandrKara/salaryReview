package com.salonreview.seo;

import com.salonreview.domain.SeoSearchMetricsSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SeoPageAnalysisServiceTest {

    private final SeoPageAnalysisService service = new SeoPageAnalysisService();
    private final LocalDate start = LocalDate.of(2026, 8, 1);
    private final LocalDate end = LocalDate.of(2026, 8, 28);

    private SeoSearchMetricsSnapshot row(LocalDate date, String query, String page, int impressions,
            BigDecimal position) {
        return SeoSearchMetricsSnapshot.builder()
                .businessId(1L).date(date).query(query).page(page)
                .clicks((int) Math.round(impressions * 0.05))
                .impressions(impressions).ctr(BigDecimal.valueOf(0.05)).position(position)
                .build();
    }

    @Test
    @DisplayName("winningPages(): a page whose impressions grow by >= 20% between halves is included")
    void winningPagesIncludesSignificantGrowth() {
        List<SeoSearchMetricsSnapshot> rows = List.of(
                row(start, "q1", "/blog/a", 100, BigDecimal.valueOf(5)),
                row(end, "q1", "/blog/a", 150, BigDecimal.valueOf(5)));

        List<SeoPageAnalysisService.PageChange> winners = service.winningPages(rows, start, end);

        assertThat(winners).hasSize(1);
        assertThat(winners.get(0).page()).isEqualTo("/blog/a");
        assertThat(winners.get(0).changeRatio()).isEqualByComparingTo("0.5");
    }

    @Test
    @DisplayName("losingPages(): a page whose impressions drop by >= 20% is included, worst first")
    void losingPagesIncludesSignificantDrop() {
        List<SeoSearchMetricsSnapshot> rows = List.of(
                row(start, "q1", "/blog/a", 100, BigDecimal.valueOf(5)),
                row(end, "q1", "/blog/a", 50, BigDecimal.valueOf(5)),
                row(start, "q2", "/blog/b", 100, BigDecimal.valueOf(5)),
                row(end, "q2", "/blog/b", 10, BigDecimal.valueOf(5)));

        List<SeoPageAnalysisService.PageChange> losers = service.losingPages(rows, start, end);

        assertThat(losers).hasSize(2);
        assertThat(losers.get(0).page()).isEqualTo("/blog/b"); // -0.9 is worse than -0.5
    }

    @Test
    @DisplayName("a small move below the 20% threshold is excluded")
    void insignificantMoveExcluded() {
        List<SeoSearchMetricsSnapshot> rows = List.of(
                row(start, "q1", "/blog/a", 100, BigDecimal.valueOf(5)),
                row(end, "q1", "/blog/a", 110, BigDecimal.valueOf(5)));

        assertThat(service.winningPages(rows, start, end)).isEmpty();
        assertThat(service.losingPages(rows, start, end)).isEmpty();
    }

    @Test
    @DisplayName("underperformingPages(): real demand with a weak position (> 10) is flagged")
    void underperformingPagesFlagsWeakPosition() {
        List<SeoSearchMetricsSnapshot> rows = List.of(row(end, "q1", "/blog/weak", 50, BigDecimal.valueOf(15)));

        List<SeoPageAnalysisService.PageOpportunity> opportunities = service.underperformingPages(rows, start, end);

        assertThat(opportunities).hasSize(1);
        assertThat(opportunities.get(0).page()).isEqualTo("/blog/weak");
    }

    @Test
    @DisplayName("underperformingPages(): a page already ranking well is not flagged")
    void underperformingPagesExcludesGoodPosition() {
        List<SeoSearchMetricsSnapshot> rows = List.of(row(end, "q1", "/", 50, BigDecimal.valueOf(3)));

        assertThat(service.underperformingPages(rows, start, end)).isEmpty();
    }

    @Test
    @DisplayName("contentOpportunities(): a page ranking in the 5-20 band with enough impressions is flagged")
    void contentOpportunitiesFlagsMidBandPosition() {
        List<SeoSearchMetricsSnapshot> rows = List.of(row(end, "q1", "/blog/mid", 50, BigDecimal.valueOf(12)));

        List<SeoPageAnalysisService.PageOpportunity> opportunities = service.contentOpportunities(rows, start, end);

        assertThat(opportunities).hasSize(1);
        assertThat(opportunities.get(0).page()).isEqualTo("/blog/mid");
    }

    @Test
    @DisplayName("cannibalizedQueries(): a query with two pages each holding a meaningful impression share is flagged")
    void cannibalizedQueriesFlagsSharedQuery() {
        List<SeoSearchMetricsSnapshot> rows = List.of(
                row(end, "russian manicure san diego", "/russian-manicure", 60, BigDecimal.valueOf(3)),
                row(end, "russian manicure san diego", "/blog/russian-manicure-explained", 40, BigDecimal.valueOf(8)));

        List<SeoPageAnalysisService.CannibalizedQuery> flagged = service.cannibalizedQueries(rows);

        assertThat(flagged).hasSize(1);
        assertThat(flagged.get(0).query()).isEqualTo("russian manicure san diego");
        assertThat(flagged.get(0).pages()).hasSize(2);
        assertThat(flagged.get(0).pages().get(0).page()).isEqualTo("/russian-manicure"); // higher impressions first
    }

    @Test
    @DisplayName("cannibalizedQueries(): a query served by only one page is not flagged")
    void cannibalizedQueriesExcludesSinglePageQuery() {
        List<SeoSearchMetricsSnapshot> rows = List.of(
                row(end, "russian manicure san diego", "/russian-manicure", 100, BigDecimal.valueOf(3)));

        assertThat(service.cannibalizedQueries(rows)).isEmpty();
    }

    @Test
    @DisplayName("cannibalizedQueries(): a second page with a negligible impression share is not counted as competing")
    void cannibalizedQueriesExcludesNegligibleShare() {
        List<SeoSearchMetricsSnapshot> rows = List.of(
                row(end, "russian manicure san diego", "/russian-manicure", 190, BigDecimal.valueOf(3)),
                row(end, "russian manicure san diego", "/old-page-still-indexed", 10, BigDecimal.valueOf(40)));

        assertThat(service.cannibalizedQueries(rows)).isEmpty();
    }

    @Test
    @DisplayName("cannibalizedQueries(): rows with no page dimension are excluded from analysis entirely")
    void cannibalizedQueriesExcludesRowsWithNoPage() {
        SeoSearchMetricsSnapshot noPage = SeoSearchMetricsSnapshot.builder()
                .businessId(1L).date(end).query("q1").page(null)
                .clicks(5).impressions(100).ctr(BigDecimal.valueOf(0.05)).position(BigDecimal.valueOf(5))
                .build();

        assertThat(service.cannibalizedQueries(List.of(noPage))).isEmpty();
        assertThat(service.winningPages(List.of(noPage), start, end)).isEmpty();
        assertThat(service.underperformingPages(List.of(noPage), start, end)).isEmpty();
    }
}
