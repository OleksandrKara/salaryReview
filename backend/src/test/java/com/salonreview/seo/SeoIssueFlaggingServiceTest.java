package com.salonreview.seo;

import com.salonreview.domain.SeoPageSnapshot;
import com.salonreview.domain.SeoSearchMetricsSnapshot;
import com.salonreview.domain.SeoTechnicalIssue;
import com.salonreview.repo.SeoTechnicalIssueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SeoIssueFlaggingServiceTest {

    private SeoTechnicalIssueRepository repository;
    private SeoIssueFlaggingService service;

    @BeforeEach
    void setUp() {
        repository = mock(SeoTechnicalIssueRepository.class);
        service = new SeoIssueFlaggingService(repository);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private SeoPageSnapshot pageSnapshot(Integer lcpMs, BigDecimal cls) {
        return SeoPageSnapshot.builder()
                .businessId(1L)
                .url("https://akluxnails.com/")
                .strategy(SeoPageSnapshot.Strategy.MOBILE)
                .performanceScore(90)
                .lcpMs(lcpMs)
                .cls(cls)
                .build();
    }

    private SeoPageSnapshot pageSnapshotFcp(Integer fcpMs) {
        return SeoPageSnapshot.builder()
                .businessId(1L)
                .url("https://akluxnails.com/")
                .strategy(SeoPageSnapshot.Strategy.MOBILE)
                .performanceScore(90)
                .fcpMs(fcpMs)
                .build();
    }

    private SeoPageSnapshot pageSnapshotTbt(Integer tbtMs) {
        return SeoPageSnapshot.builder()
                .businessId(1L)
                .url("https://akluxnails.com/")
                .strategy(SeoPageSnapshot.Strategy.MOBILE)
                .performanceScore(90)
                .tbtMs(tbtMs)
                .build();
    }

    @Test
    @DisplayName("LCP exactly at 2500ms does not flag")
    void lcpAtGoodBoundaryDoesNotFlag() {
        when(repository.findOpenBySubject(1L, SeoTechnicalIssue.IssueType.LCP, "https://akluxnails.com/", null, SeoPageSnapshot.Strategy.MOBILE))
                .thenReturn(Optional.empty());

        service.evaluatePageSnapshot(pageSnapshot(2500, BigDecimal.ZERO));

        verify(repository, never()).save(argThat(i -> i.getIssueType() == SeoTechnicalIssue.IssueType.LCP));
    }

    @Test
    @DisplayName("LCP at 2501ms flags as NEEDS_IMPROVEMENT")
    void lcpJustOverGoodFlagsNeedsImprovement() {
        when(repository.findOpenBySubject(1L, SeoTechnicalIssue.IssueType.LCP, "https://akluxnails.com/", null, SeoPageSnapshot.Strategy.MOBILE))
                .thenReturn(Optional.empty());

        service.evaluatePageSnapshot(pageSnapshot(2501, BigDecimal.ZERO));

        verify(repository).save(argThat(i -> i.getIssueType() == SeoTechnicalIssue.IssueType.LCP
                && i.getSeverity() == SeoTechnicalIssue.Severity.NEEDS_IMPROVEMENT
                && i.getMetricValue().intValue() == 2501));
    }

    @Test
    @DisplayName("LCP exactly at 4000ms is still NEEDS_IMPROVEMENT, not POOR")
    void lcpAtPoorBoundaryIsNeedsImprovement() {
        when(repository.findOpenBySubject(any(), any(), any(), any(), any())).thenReturn(Optional.empty());

        service.evaluatePageSnapshot(pageSnapshot(4000, BigDecimal.ZERO));

        verify(repository).save(argThat(i -> i.getSeverity() == SeoTechnicalIssue.Severity.NEEDS_IMPROVEMENT));
    }

    @Test
    @DisplayName("LCP at 4001ms flags as POOR")
    void lcpJustOverPoorFlagsPoor() {
        when(repository.findOpenBySubject(any(), any(), any(), any(), any())).thenReturn(Optional.empty());

        service.evaluatePageSnapshot(pageSnapshot(4001, BigDecimal.ZERO));

        verify(repository).save(argThat(i -> i.getIssueType() == SeoTechnicalIssue.IssueType.LCP
                && i.getSeverity() == SeoTechnicalIssue.Severity.POOR));
    }

    @Test
    @DisplayName("CLS exactly at 0.1 does not flag, 0.11 flags NEEDS_IMPROVEMENT, 0.26 flags POOR")
    void clsBoundaries() {
        when(repository.findOpenBySubject(any(), any(), any(), any(), any())).thenReturn(Optional.empty());

        service.evaluatePageSnapshot(pageSnapshot(1000, BigDecimal.valueOf(0.1)));
        verify(repository, never()).save(argThat(i -> i.getIssueType() == SeoTechnicalIssue.IssueType.CLS));

        service.evaluatePageSnapshot(pageSnapshot(1000, BigDecimal.valueOf(0.11)));
        verify(repository).save(argThat(i -> i.getIssueType() == SeoTechnicalIssue.IssueType.CLS
                && i.getSeverity() == SeoTechnicalIssue.Severity.NEEDS_IMPROVEMENT));

        service.evaluatePageSnapshot(pageSnapshot(1000, BigDecimal.valueOf(0.26)));
        verify(repository).save(argThat(i -> i.getIssueType() == SeoTechnicalIssue.IssueType.CLS
                && i.getSeverity() == SeoTechnicalIssue.Severity.POOR));
    }

    @Test
    @DisplayName("FCP exactly at 1800ms does not flag, 1801ms flags NEEDS_IMPROVEMENT, 3001ms flags POOR")
    void fcpBoundaries() {
        when(repository.findOpenBySubject(any(), any(), any(), any(), any())).thenReturn(Optional.empty());

        service.evaluatePageSnapshot(pageSnapshotFcp(1800));
        verify(repository, never()).save(argThat(i -> i.getIssueType() == SeoTechnicalIssue.IssueType.FCP));

        service.evaluatePageSnapshot(pageSnapshotFcp(1801));
        verify(repository).save(argThat(i -> i.getIssueType() == SeoTechnicalIssue.IssueType.FCP
                && i.getSeverity() == SeoTechnicalIssue.Severity.NEEDS_IMPROVEMENT));

        service.evaluatePageSnapshot(pageSnapshotFcp(3001));
        verify(repository).save(argThat(i -> i.getIssueType() == SeoTechnicalIssue.IssueType.FCP
                && i.getSeverity() == SeoTechnicalIssue.Severity.POOR));
    }

    @Test
    @DisplayName("TBT exactly at 200ms does not flag, 201ms flags NEEDS_IMPROVEMENT, 601ms flags POOR")
    void tbtBoundaries() {
        when(repository.findOpenBySubject(any(), any(), any(), any(), any())).thenReturn(Optional.empty());

        service.evaluatePageSnapshot(pageSnapshotTbt(200));
        verify(repository, never()).save(argThat(i -> i.getIssueType() == SeoTechnicalIssue.IssueType.TBT));

        service.evaluatePageSnapshot(pageSnapshotTbt(201));
        verify(repository).save(argThat(i -> i.getIssueType() == SeoTechnicalIssue.IssueType.TBT
                && i.getSeverity() == SeoTechnicalIssue.Severity.NEEDS_IMPROVEMENT));

        service.evaluatePageSnapshot(pageSnapshotTbt(601));
        verify(repository).save(argThat(i -> i.getIssueType() == SeoTechnicalIssue.IssueType.TBT
                && i.getSeverity() == SeoTechnicalIssue.Severity.POOR));
    }

    @Test
    @DisplayName("Null FCP/TBT (partial Lighthouse run) is skipped, not flagged")
    void nullFcpAndTbtAreSkipped() {
        service.evaluatePageSnapshot(pageSnapshot(1000, BigDecimal.ZERO));
        verify(repository, never()).save(argThat(i -> i.getIssueType() == SeoTechnicalIssue.IssueType.FCP
                || i.getIssueType() == SeoTechnicalIssue.IssueType.TBT));
    }

    @Test
    @DisplayName("a later good snapshot auto-resolves a previously open LCP issue")
    void laterGoodSnapshotAutoResolves() {
        SeoTechnicalIssue open = SeoTechnicalIssue.builder()
                .id(42L).businessId(1L).issueType(SeoTechnicalIssue.IssueType.LCP)
                .url("https://akluxnails.com/").severity(SeoTechnicalIssue.Severity.POOR)
                .detail("old").metricValue(BigDecimal.valueOf(5000)).firstSeenAt(Instant.now())
                .build();
        when(repository.findOpenBySubject(1L, SeoTechnicalIssue.IssueType.LCP, "https://akluxnails.com/", null, SeoPageSnapshot.Strategy.MOBILE))
                .thenReturn(Optional.of(open));

        service.evaluatePageSnapshot(pageSnapshot(2000, BigDecimal.ZERO));

        assertThat(open.getResolvedAt()).isNotNull();
        verify(repository).save(open);
    }

    @Test
    @DisplayName("an existing open issue is updated in place, not duplicated, while still poor")
    void existingOpenIssueUpdatedNotDuplicated() {
        SeoTechnicalIssue open = SeoTechnicalIssue.builder()
                .id(42L).businessId(1L).issueType(SeoTechnicalIssue.IssueType.LCP)
                .url("https://akluxnails.com/").severity(SeoTechnicalIssue.Severity.NEEDS_IMPROVEMENT)
                .detail("old").metricValue(BigDecimal.valueOf(2600)).firstSeenAt(Instant.now())
                .build();
        when(repository.findOpenBySubject(1L, SeoTechnicalIssue.IssueType.LCP, "https://akluxnails.com/", null, SeoPageSnapshot.Strategy.MOBILE))
                .thenReturn(Optional.of(open));

        service.evaluatePageSnapshot(pageSnapshot(4500, BigDecimal.ZERO));

        assertThat(open.getSeverity()).isEqualTo(SeoTechnicalIssue.Severity.POOR);
        assertThat(open.getMetricValue().intValue()).isEqualTo(4500);
        assertThat(open.getResolvedAt()).isNull();
        verify(repository, times(1)).save(any());
    }

    @Test
    @DisplayName("a good desktop LCP never auto-resolves an open mobile LCP issue for the same URL "
            + "(real bug found live 2026-09-01 — mobile/desktop share a URL but not a pass/fail state)")
    void differentStrategiesForSameUrlDoNotCrossResolve() {
        SeoTechnicalIssue openMobileIssue = SeoTechnicalIssue.builder()
                .id(99L).businessId(1L).issueType(SeoTechnicalIssue.IssueType.LCP)
                .url("https://akluxnails.com/").strategy(SeoPageSnapshot.Strategy.MOBILE)
                .severity(SeoTechnicalIssue.Severity.POOR).detail("old")
                .metricValue(BigDecimal.valueOf(6000)).firstSeenAt(Instant.now())
                .build();
        // The mobile-scoped lookup finds the real open issue; the desktop-scoped lookup must be a
        // *different* key entirely and find nothing, per the fixed findOpenBySubject signature.
        when(repository.findOpenBySubject(1L, SeoTechnicalIssue.IssueType.LCP, "https://akluxnails.com/",
                null, SeoPageSnapshot.Strategy.MOBILE)).thenReturn(Optional.of(openMobileIssue));
        when(repository.findOpenBySubject(1L, SeoTechnicalIssue.IssueType.LCP, "https://akluxnails.com/",
                null, SeoPageSnapshot.Strategy.DESKTOP)).thenReturn(Optional.empty());

        SeoPageSnapshot desktopGood = SeoPageSnapshot.builder()
                .businessId(1L).url("https://akluxnails.com/").strategy(SeoPageSnapshot.Strategy.DESKTOP)
                .performanceScore(97).lcpMs(1200).cls(BigDecimal.ZERO).build();

        service.evaluatePageSnapshot(desktopGood);

        assertThat(openMobileIssue.getResolvedAt())
                .as("desktop's good LCP must never resolve mobile's still-open, still-real issue")
                .isNull();
        verify(repository, never()).save(openMobileIssue);
    }

    @Test
    @DisplayName("CTR heuristic: a query under 50 impressions is never flagged regardless of CTR")
    void ctrBelowImpressionFloorNeverFlagged() {
        SeoSearchMetricsSnapshot lowImpressions = searchRow("nail salon rare term", "/", 49, BigDecimal.valueOf(0.001));
        SeoSearchMetricsSnapshot normal = searchRow("nail salon san diego", "/", 500, BigDecimal.valueOf(0.10));

        service.evaluateSearchMetrics(1L, List.of(lowImpressions, normal));

        verify(repository, never()).save(argThat(i -> "nail salon rare term".equals(i.getQuery())));
    }

    @Test
    @DisplayName("CTR heuristic: a query under half the trailing average CTR is flagged ADVISORY")
    void ctrUnderHalfAverageIsFlagged() {
        // average of (0.10, 0.10, 0.02) = 0.0733..., half = 0.0366... -> 0.02 row should flag
        SeoSearchMetricsSnapshot high1 = searchRow("q1", "/", 100, BigDecimal.valueOf(0.10));
        SeoSearchMetricsSnapshot high2 = searchRow("q2", "/blog", 100, BigDecimal.valueOf(0.10));
        SeoSearchMetricsSnapshot low = searchRow("q3", "/blog/gel-overlay", 100, BigDecimal.valueOf(0.02));
        when(repository.findOpenBySubject(any(), any(), any(), any(), any())).thenReturn(Optional.empty());

        service.evaluateSearchMetrics(1L, List.of(high1, high2, low));

        verify(repository).save(argThat(i -> i.getIssueType() == SeoTechnicalIssue.IssueType.CTR_OPPORTUNITY
                && "q3".equals(i.getQuery())
                && i.getSeverity() == SeoTechnicalIssue.Severity.ADVISORY));
        verify(repository, never()).save(argThat(i -> "q1".equals(i.getQuery()) || "q2".equals(i.getQuery())));
    }

    @Test
    @DisplayName("CTR heuristic: a query back above half-average auto-resolves its open issue")
    void ctrRecoveryAutoResolves() {
        SeoTechnicalIssue open = SeoTechnicalIssue.builder()
                .id(7L).businessId(1L).issueType(SeoTechnicalIssue.IssueType.CTR_OPPORTUNITY)
                .url("/blog/gel-overlay").query("q3").severity(SeoTechnicalIssue.Severity.ADVISORY)
                .detail("old").metricValue(BigDecimal.valueOf(0.02)).firstSeenAt(Instant.now())
                .build();
        when(repository.findOpenBySubject(1L, SeoTechnicalIssue.IssueType.CTR_OPPORTUNITY, "/blog/gel-overlay", "q3", null))
                .thenReturn(Optional.of(open));

        SeoSearchMetricsSnapshot recovered = searchRow("q3", "/blog/gel-overlay", 100, BigDecimal.valueOf(0.10));
        SeoSearchMetricsSnapshot other = searchRow("q1", "/", 100, BigDecimal.valueOf(0.10));

        service.evaluateSearchMetrics(1L, List.of(recovered, other));

        assertThat(open.getResolvedAt()).isNotNull();
    }

    private SeoSearchMetricsSnapshot searchRow(String query, String page, int impressions, BigDecimal ctr) {
        return SeoSearchMetricsSnapshot.builder()
                .businessId(1L).query(query).page(page)
                .clicks((int) Math.round(impressions * ctr.doubleValue()))
                .impressions(impressions).ctr(ctr).position(BigDecimal.valueOf(5))
                .build();
    }
}
