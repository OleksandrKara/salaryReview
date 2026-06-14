package com.salonreview.square;

import com.salonreview.domain.Half;
import com.salonreview.domain.PayPeriod;
import com.salonreview.domain.PeriodEntry;
import com.salonreview.domain.Provider;
import com.salonreview.domain.RevenueSnapshot;
import com.salonreview.repo.PayPeriodRepository;
import com.salonreview.repo.PeriodEntryRepository;
import com.salonreview.repo.RevenueSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RevenueForecastServiceTest {

    private PayPeriodRepository payPeriods;
    private PeriodEntryRepository entries;
    private RevenueSnapshotRepository snapshots;
    private RevenueForecastService service;

    private long nextId = 1;

    @BeforeEach
    void setUp() {
        payPeriods = mock(PayPeriodRepository.class);
        entries    = mock(PeriodEntryRepository.class);
        snapshots  = mock(RevenueSnapshotRepository.class);
        service    = new RevenueForecastService(payPeriods, entries, snapshots);

        when(payPeriods.findAllByYearOrderByMonthAscHalfAsc(anyInt())).thenReturn(List.of());
        when(entries.findAllByPayPeriodId(anyLong())).thenReturn(List.of());
        when(snapshots.findAllByMonthEndActualIsNotNullOrderBySnapshotDateDesc(any(Pageable.class)))
                .thenReturn(List.of());
    }

    @Test
    @DisplayName("Cold start: no history → naive fallback, no range")
    void coldStart_naiveOnly() {
        ForecastResult r = service.forecast(2026, 6, bd("7890"), bd("8221"));

        assertThat(r.projectedMid()).isEqualByComparingTo("16111.00");
        assertThat(r.projectedLow()).isNull();
        assertThat(r.projectedHigh()).isNull();
        assertThat(r.historyMonths()).isEqualTo(0);
        assertThat(r.calibrationDataPoints()).isEqualTo(0);
    }

    @Test
    @DisplayName("Pattern-only path: 6 settled months, no calibration → mid uses pattern, ±15% range")
    void patternOnly_usesPatternAndWideRange() {
        // 6 settled months where first half is consistently 50% of total: avg ratio = 0.5
        // currentMTD $7,890 / 0.5 = $15,780 pattern projection
        seedSettledMonths(2026, 5, 6, 5000, 5000);

        ForecastResult r = service.forecast(2026, 6, bd("7890"), bd("8221"));

        assertThat(r.projectedMid()).isEqualByComparingTo("15780.00");
        // Single technique → ±15%
        assertThat(r.projectedLow()).isEqualByComparingTo("13413.00");
        assertThat(r.projectedHigh()).isEqualByComparingTo("18147.00");
        assertThat(r.historyMonths()).isEqualTo(6);
        assertThat(r.calibrationDataPoints()).isEqualTo(0);
    }

    @Test
    @DisplayName("Pattern + calibration blend: weighted mid + bracket range")
    void fullBlend_weightsAndAsymmetricRange() {
        // Pattern: 6 months at 50/50 → avg ratio 0.5 → pattern projection = 7890 / 0.5 = $15,780
        seedSettledMonths(2026, 5, 6, 5000, 5000);
        // Calibration: 6 closed months where actual = 0.92 × (mtd + upcoming).
        // For naive = 7890 + 8221 = 16111, calibrated = 16111 × 0.92 = $14,822.12
        seedCalibrationSnapshots(6, "0.92");

        ForecastResult r = service.forecast(2026, 6, bd("7890"), bd("8221"));

        // 6+ calibration rows → pattern weight 0.3, calibration weight 0.7
        // mid = 15780 × 0.3 + 14822.12 × 0.7 = 4734 + 10375.48 = 15109.49
        assertThat(r.projectedMid().doubleValue()).isCloseTo(15109.49, within(1.0));
        // Range brackets min × 0.9 and max × 1.1
        assertThat(r.projectedLow().doubleValue()).isCloseTo(14822.12 * 0.9, within(1.0));
        assertThat(r.projectedHigh().doubleValue()).isCloseTo(15780.0 * 1.1, within(1.0));
        assertThat(r.calibrationDataPoints()).isEqualTo(6);
    }

    @Test
    @DisplayName("Below 3 calibration rows: calibration disabled, pattern-only result")
    void calibrationBelowMinimum_falls_back_to_pattern() {
        seedSettledMonths(2026, 5, 6, 5000, 5000);  // pattern projection = 15780
        seedCalibrationSnapshots(2, "0.92");        // not enough to engage calibration

        ForecastResult r = service.forecast(2026, 6, bd("7890"), bd("8221"));

        // Same as pattern-only path — calibration returns null below MIN_CALIBRATION_ROWS
        assertThat(r.projectedMid()).isEqualByComparingTo("15780.00");
        assertThat(r.projectedLow()).isEqualByComparingTo("13413.00");
        assertThat(r.projectedHigh()).isEqualByComparingTo("18147.00");
        assertThat(r.calibrationDataPoints()).isEqualTo(2);
    }

    // --- helpers ---

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    private Provider provider() {
        return Provider.builder().id(1L).name("Test").displayName("Test")
                .commissionRate(bd("0.45")).cardTipFeeRate(bd("0.035")).active(true).build();
    }

    /** Plant N settled months ending at (year, month) with the given first/second-half revenue. */
    private void seedSettledMonths(int year, int month, int count, double firstHalfRev, double secondHalfRev) {
        Provider p = provider();
        java.time.YearMonth cursor = java.time.YearMonth.of(year, month);
        for (int i = 0; i < count; i++) {
            int yr = cursor.getYear(), mo = cursor.getMonthValue();
            PayPeriod first  = PayPeriod.builder().id(nextId++).year(yr).month(mo).half(Half.FIRST).label("first").build();
            PayPeriod second = PayPeriod.builder().id(nextId++).year(yr).month(mo).half(Half.SECOND).label("second").build();
            // findAllByYearOrderByMonthAscHalfAsc(yr) needs to include both halves of this month
            List<PayPeriod> yrPeriods = new ArrayList<>(java.util.Optional.ofNullable(
                    payPeriodMockReturns.get(yr)).orElse(List.of()));
            yrPeriods.add(first);
            yrPeriods.add(second);
            payPeriodMockReturns.put(yr, yrPeriods);
            when(payPeriods.findAllByYearOrderByMonthAscHalfAsc(yr)).thenReturn(yrPeriods);

            PeriodEntry firstEntry  = entryWith(p, first,  firstHalfRev);
            PeriodEntry secondEntry = entryWith(p, second, secondHalfRev);
            when(entries.findAllByPayPeriodId(first.getId())).thenReturn(List.of(firstEntry));
            when(entries.findAllByPayPeriodId(second.getId())).thenReturn(List.of(secondEntry));

            cursor = cursor.minusMonths(1);
        }
    }

    private final java.util.Map<Integer, List<PayPeriod>> payPeriodMockReturns = new java.util.HashMap<>();

    private PeriodEntry entryWith(Provider p, PayPeriod pp, double rev) {
        return PeriodEntry.builder()
                .provider(p).payPeriod(pp)
                .cardTotal(BigDecimal.valueOf(rev))
                .cashTotal(BigDecimal.ZERO)
                .cardTips(BigDecimal.ZERO)
                .adjustmentsAmount(BigDecimal.ZERO)
                .procedures(1)
                .build();
    }

    /** Plant N closed snapshot rows, one per month, with the given month_end_actual / naive ratio. */
    private void seedCalibrationSnapshots(int count, String biasRatioStr) {
        BigDecimal biasRatio = bd(biasRatioStr);
        List<RevenueSnapshot> rows = new ArrayList<>();
        LocalDate cursor = LocalDate.of(2026, 5, 15);
        for (int i = 0; i < count; i++) {
            BigDecimal mtd = bd("7000.00");
            BigDecimal upcoming = bd("8000.00");
            BigDecimal actual = mtd.add(upcoming).multiply(biasRatio).setScale(2, java.math.RoundingMode.HALF_UP);
            rows.add(RevenueSnapshot.builder()
                    .id((long)(100 + i))
                    .snapshotDate(cursor)
                    .mtdRevenue(mtd)
                    .mtdCard(mtd).mtdCash(BigDecimal.ZERO)
                    .mtdServices(50)
                    .upcomingCount(20).upcomingGross(upcoming)
                    .monthEndActual(actual)
                    .createdAt(java.time.Instant.now())
                    .build());
            cursor = cursor.minusMonths(1);
        }
        when(snapshots.findAllByMonthEndActualIsNotNullOrderBySnapshotDateDesc(any(Pageable.class)))
                .thenReturn(rows);
    }
}
