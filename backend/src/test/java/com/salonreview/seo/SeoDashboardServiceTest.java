package com.salonreview.seo;

import com.salonreview.domain.SeoConnection;
import com.salonreview.domain.SeoPageSnapshot;
import com.salonreview.domain.SeoSearchMetricsSnapshot;
import com.salonreview.domain.SeoTechnicalIssue;
import com.salonreview.repo.SeoConnectionRepository;
import com.salonreview.repo.SeoPageSnapshotRepository;
import com.salonreview.repo.SeoSearchMetricsSnapshotRepository;
import com.salonreview.repo.SeoTechnicalIssueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SeoDashboardServiceTest {

    private SeoConnectionRepository connectionRepository;
    private SeoSearchMetricsSnapshotRepository searchMetricsRepository;
    private SeoPageSnapshotRepository pageSnapshotRepository;
    private SeoTechnicalIssueRepository issueRepository;
    private SeoDashboardService service;

    @BeforeEach
    void setUp() {
        connectionRepository = mock(SeoConnectionRepository.class);
        searchMetricsRepository = mock(SeoSearchMetricsSnapshotRepository.class);
        pageSnapshotRepository = mock(SeoPageSnapshotRepository.class);
        issueRepository = mock(SeoTechnicalIssueRepository.class);
        service = new SeoDashboardService(connectionRepository, searchMetricsRepository, pageSnapshotRepository, issueRepository);
    }

    @Test
    @DisplayName("no seo_connection row returns a fully empty, not-connected overview")
    void noConnectionReturnsEmptyOverview() {
        when(connectionRepository.findByBusinessId(1L)).thenReturn(Optional.empty());

        SeoDashboardService.Overview overview = service.overview(1L, 28);

        assertThat(overview.connected()).isFalse();
        assertThat(overview.trend()).isEmpty();
        assertThat(overview.topQueries()).isEmpty();
        assertThat(overview.mobile()).isNull();
        assertThat(overview.activeIssues()).isEmpty();
    }

    @Test
    @DisplayName("trend aggregates same-day rows: sums clicks/impressions, weighted-average position")
    void trendAggregatesSameDayRows() {
        LocalDate day = LocalDate.of(2026, 9, 1);
        SeoConnection connection = SeoConnection.builder().businessId(1L).build();
        when(connectionRepository.findByBusinessId(1L)).thenReturn(Optional.of(connection));
        when(searchMetricsRepository.findByBusinessIdAndDateBetweenOrderByDateAsc(any(), any(), any()))
                .thenReturn(List.of(
                        row(day, "q1", "/", 10, 100, BigDecimal.valueOf(0.10), BigDecimal.valueOf(2)),
                        row(day, "q2", "/blog", 5, 100, BigDecimal.valueOf(0.05), BigDecimal.valueOf(8))));
        when(pageSnapshotRepository.findFirstByBusinessIdAndStrategyOrderByDateDesc(any(), any())).thenReturn(Optional.empty());
        when(issueRepository.findByBusinessIdAndResolvedAtIsNull(any())).thenReturn(List.of());

        SeoDashboardService.Overview overview = service.overview(1L, 28);

        assertThat(overview.trend()).hasSize(1);
        SeoDashboardService.TrendPoint point = overview.trend().get(0);
        assertThat(point.clicks()).isEqualTo(15);
        assertThat(point.impressions()).isEqualTo(200);
        // weighted position: (2*100 + 8*100) / 200 = 5.00
        assertThat(point.position()).isEqualByComparingTo(BigDecimal.valueOf(5.00));
    }

    @Test
    @DisplayName("top queries are sorted by clicks descending and capped at 20")
    void topQueriesSortedAndCapped() {
        LocalDate day = LocalDate.of(2026, 9, 1);
        when(connectionRepository.findByBusinessId(1L)).thenReturn(Optional.of(SeoConnection.builder().businessId(1L).build()));
        List<SeoSearchMetricsSnapshot> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) {
            rows.add(row(day, "query-" + i, "/", i, i + 1, BigDecimal.valueOf(0.1), BigDecimal.valueOf(3)));
        }
        when(searchMetricsRepository.findByBusinessIdAndDateBetweenOrderByDateAsc(any(), any(), any())).thenReturn(rows);
        when(pageSnapshotRepository.findFirstByBusinessIdAndStrategyOrderByDateDesc(any(), any())).thenReturn(Optional.empty());
        when(issueRepository.findByBusinessIdAndResolvedAtIsNull(any())).thenReturn(List.of());

        SeoDashboardService.Overview overview = service.overview(1L, 28);

        assertThat(overview.topQueries()).hasSize(20);
        assertThat(overview.topQueries().get(0).query()).isEqualTo("query-24");
        assertThat(overview.topQueries().get(19).query()).isEqualTo("query-5");
    }

    @Test
    @DisplayName("latest mobile/desktop CWV snapshots and active issues are surfaced")
    void latestVitalsAndIssuesSurfaced() {
        when(connectionRepository.findByBusinessId(1L)).thenReturn(Optional.of(
                SeoConnection.builder().businessId(1L).lastSyncAt(Instant.parse("2026-09-01T00:00:00Z")).build()));
        when(searchMetricsRepository.findByBusinessIdAndDateBetweenOrderByDateAsc(any(), any(), any())).thenReturn(List.of());
        SeoPageSnapshot mobile = SeoPageSnapshot.builder().businessId(1L).date(LocalDate.of(2026, 9, 1))
                .url("https://akluxnails.com/").strategy(SeoPageSnapshot.Strategy.MOBILE)
                .performanceScore(90).lcpMs(3500).cls(BigDecimal.ZERO).build();
        when(pageSnapshotRepository.findFirstByBusinessIdAndStrategyOrderByDateDesc(1L, SeoPageSnapshot.Strategy.MOBILE))
                .thenReturn(Optional.of(mobile));
        when(pageSnapshotRepository.findFirstByBusinessIdAndStrategyOrderByDateDesc(1L, SeoPageSnapshot.Strategy.DESKTOP))
                .thenReturn(Optional.empty());
        SeoTechnicalIssue issue = SeoTechnicalIssue.builder().businessId(1L).issueType(SeoTechnicalIssue.IssueType.LCP)
                .severity(SeoTechnicalIssue.Severity.NEEDS_IMPROVEMENT).detail("LCP is slow")
                .url("https://akluxnails.com/").firstSeenAt(Instant.now()).build();
        when(issueRepository.findByBusinessIdAndResolvedAtIsNull(1L)).thenReturn(List.of(issue));

        SeoDashboardService.Overview overview = service.overview(1L, 28);

        assertThat(overview.connected()).isTrue();
        assertThat(overview.mobile().lcpMs()).isEqualTo(3500);
        assertThat(overview.desktop()).isNull();
        assertThat(overview.activeIssues()).hasSize(1);
        assertThat(overview.activeIssues().get(0).issueType()).isEqualTo("LCP");
    }

    private static SeoSearchMetricsSnapshot row(LocalDate date, String query, String page, int clicks,
            int impressions, BigDecimal ctr, BigDecimal position) {
        return SeoSearchMetricsSnapshot.builder().businessId(1L).date(date).query(query).page(page)
                .clicks(clicks).impressions(impressions).ctr(ctr).position(position).build();
    }
}
