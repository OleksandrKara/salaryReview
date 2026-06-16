package com.salonreview.square;

import com.salonreview.domain.Half;
import com.salonreview.domain.PayPeriod;
import com.salonreview.domain.PeriodEntry;
import com.salonreview.domain.Provider;
import com.salonreview.domain.RevenueSnapshot;
import com.salonreview.domain.SalonConfig;
import com.salonreview.repo.PayPeriodRepository;
import com.salonreview.repo.PeriodEntryRepository;
import com.salonreview.repo.RevenueSnapshotRepository;
import com.salonreview.repo.SalonConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RevenueSnapshotServiceTest {

    private RevenueSnapshotRepository repo;
    private SquareMonthAggregator aggregator;
    private SquareClient square;
    private SalonConfigRepository salonConfig;
    private PayPeriodRepository payPeriods;
    private PeriodEntryRepository entries;
    private RevenueSnapshotService service;

    @BeforeEach
    void setUp() {
        repo        = mock(RevenueSnapshotRepository.class);
        aggregator  = mock(SquareMonthAggregator.class);
        square      = mock(SquareClient.class);
        salonConfig = mock(SalonConfigRepository.class);
        payPeriods  = mock(PayPeriodRepository.class);
        entries     = mock(PeriodEntryRepository.class);
        service     = new RevenueSnapshotService(repo, aggregator, square, salonConfig, payPeriods, entries);

        when(salonConfig.findById(1)).thenReturn(Optional.of(SalonConfig.builder()
                .id(1).ownerShortName("o").tierServiceThreshold(25)
                .servicePriceCutoff(new BigDecimal("60.00"))
                .baseCommissionRate(new BigDecimal("0.45"))
                .tierCommissionRate(new BigDecimal("0.50"))
                .cardTipFeeRate(new BigDecimal("0.035")).build()));
        when(square.locationTimeZone()).thenReturn("UTC");
        when(square.bookings(any(), any())).thenReturn(List.of());
        when(square.catalogPrices(any())).thenReturn(java.util.Map.of());
        when(aggregator.aggregate(org.mockito.ArgumentMatchers.anyInt(),
                                   org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenReturn(new SquareMonthAggregator.MonthAggregation(
                        2026, 6, "UTC", List.of(), new SquareMonthAggregator.Diag(),
                        List.of(), List.of(), List.of()));
    }

    @Test
    @DisplayName("captureFor is idempotent — re-run on same date is a no-op")
    void idempotentRecapture() {
        LocalDate date = LocalDate.of(2026, 6, 14);
        when(repo.findBySnapshotDate(date)).thenReturn(Optional.of(
                RevenueSnapshot.builder().id(1L).snapshotDate(date).build()));

        service.captureFor(date);

        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("backfillRecent captures last 3 days (today excluded)")
    void backfillRecentCapturesThreeDays() {
        when(repo.findBySnapshotDate(any())).thenReturn(Optional.empty());

        service.backfillRecent();

        ArgumentCaptor<RevenueSnapshot> cap = ArgumentCaptor.forClass(RevenueSnapshot.class);
        verify(repo, org.mockito.Mockito.atLeast(3)).save(cap.capture());
        // All captured dates are within the last 3 days
        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        for (RevenueSnapshot r : cap.getAllValues()) {
            assertThat(r.getSnapshotDate()).isBefore(today);
            assertThat(r.getSnapshotDate()).isAfterOrEqualTo(today.minusDays(3));
        }
    }

    @Test
    @DisplayName("fillMonthEndActualsFor writes actual to every snapshot in the month")
    void fillMonthEndActualsForWritesAll() {
        YearMonth may = YearMonth.of(2026, 5);
        Provider p = Provider.builder().id(1L).name("T").displayName("T")
                .commissionRate(new BigDecimal("0.45")).cardTipFeeRate(new BigDecimal("0.035")).active(true).build();
        PayPeriod first  = PayPeriod.builder().id(10L).year(2026).month(5).half(Half.FIRST).label("f").build();
        PayPeriod second = PayPeriod.builder().id(11L).year(2026).month(5).half(Half.SECOND).label("s").build();
        when(payPeriods.findAllByYearOrderByMonthAscHalfAsc(2026)).thenReturn(List.of(first, second));
        when(entries.findAllByPayPeriodId(10L)).thenReturn(List.of(entry(p, first, "5000")));
        when(entries.findAllByPayPeriodId(11L)).thenReturn(List.of(entry(p, second, "6000")));

        RevenueSnapshot a = RevenueSnapshot.builder().id(1L).snapshotDate(LocalDate.of(2026,5,5)).build();
        RevenueSnapshot b = RevenueSnapshot.builder().id(2L).snapshotDate(LocalDate.of(2026,5,15)).build();
        when(repo.findAllBySnapshotDateBetween(may.atDay(1), may.atEndOfMonth()))
                .thenReturn(List.of(a, b));

        int updated = service.fillMonthEndActualsFor(may);

        assertThat(updated).isEqualTo(2);
        assertThat(a.getMonthEndActual()).isEqualByComparingTo("11000.00");
        assertThat(b.getMonthEndActual()).isEqualByComparingTo("11000.00");
    }

    @Test
    @DisplayName("fillMonthEndActualsFor logs warning and returns 0 when no PeriodEntry rows")
    void fillMonthEndActualsForNoData() {
        YearMonth may = YearMonth.of(2026, 5);
        when(payPeriods.findAllByYearOrderByMonthAscHalfAsc(2026)).thenReturn(List.of());

        int updated = service.fillMonthEndActualsFor(may);

        assertThat(updated).isEqualTo(0);
    }

    private static PeriodEntry entry(Provider p, PayPeriod pp, String card) {
        return PeriodEntry.builder().provider(p).payPeriod(pp)
                .cardTotal(new BigDecimal(card))
                .cashTotal(BigDecimal.ZERO).cardTips(BigDecimal.ZERO)
                .adjustmentsAmount(BigDecimal.ZERO).procedures(1).build();
    }
}
