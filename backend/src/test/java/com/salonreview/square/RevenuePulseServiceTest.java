package com.salonreview.square;

import com.salonreview.domain.RevenueSnapshot;
import com.salonreview.domain.SalonConfig;
import com.salonreview.repo.RevenueSnapshotRepository;
import com.salonreview.repo.SalonConfigRepository;
import com.salonreview.square.SquareMonthAggregator.AttributedService;
import com.salonreview.square.SquareMonthAggregator.MonthAggregation;
import com.salonreview.web.dto.RevenuePulseDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RevenuePulseService}: card vs cash is derived from the month aggregator's
 * channel attribution (so cash includes CASH-NOTE, matching /overview), and the projection is split
 * by the realized card:cash mix.
 */
class RevenuePulseServiceTest {

    private SquareClient square;
    private SquareClientProvider squareClientProvider;
    private RevenueForecastService forecaster;
    private SquareMonthAggregator aggregator;
    private SalonConfigRepository salonConfig;
    private com.salonreview.config.CurrentBusinessContext currentBusinessContext;
    private RevenueSnapshotRepository snapshots;
    private ManualAdjustmentService manualAdjustments;
    private RevenuePulseService service;

    @BeforeEach
    void setUp() {
        square = mock(SquareClient.class);
        forecaster = mock(RevenueForecastService.class);
        aggregator = mock(SquareMonthAggregator.class);
        salonConfig = mock(SalonConfigRepository.class);
        snapshots = mock(RevenueSnapshotRepository.class);
        manualAdjustments = mock(ManualAdjustmentService.class);

        when(square.locationTimeZone()).thenReturn("UTC");
        when(square.bookings(any(), any())).thenReturn(List.of());
        currentBusinessContext = mock(com.salonreview.config.CurrentBusinessContext.class);
        when(currentBusinessContext.id()).thenReturn(1L);
        squareClientProvider = mock(SquareClientProvider.class);
        when(squareClientProvider.forBusiness(1L)).thenReturn(square);
        // pulse() wraps its two CompletableFuture.supplyAsync tasks in runAsAndGet(businessId, Supplier)
        // so the worker thread sees the business id (see the async ThreadLocal fix on this class) — a
        // plain mock's runAsAndGet() is a no-op that never invokes the wrapped supplier (it would
        // return null), so make it actually run.
        when(currentBusinessContext.runAsAndGet(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.<java.util.function.Supplier<Object>>any()))
                .thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(1)).get());
        when(salonConfig.findByBusinessId(1L)).thenReturn(Optional.of(SalonConfig.builder()
                .id(1).ownerShortName("o").servicePriceCutoff(new BigDecimal("60.00")).build()));
        when(snapshots.findByBusinessIdAndSnapshotDate(eq(1L), any())).thenReturn(Optional.empty());
        // No manual adjustments by default — individual tests override to exercise the fold-in.
        when(manualAdjustments.totalGrossThrough(any())).thenReturn(BigDecimal.ZERO);

        service = new RevenuePulseService(squareClientProvider, forecaster, aggregator, salonConfig, currentBusinessContext,
                snapshots, manualAdjustments);
    }

    private static AttributedService svc(String date, String channel, String gross) {
        return svcAt(date, null, channel, gross);
    }

    private static AttributedService svcAt(String date, String time, String channel, String gross) {
        return new AttributedService("p1", "P", date, "FIRST", "Manicure", new BigDecimal(gross),
                BigDecimal.ZERO, new BigDecimal(gross), BigDecimal.ZERO, true, 1, 1, false, channel,
                time, null, null, null);
    }

    private static MonthAggregation aggOf(int year, int month, List<AttributedService> services) {
        return new MonthAggregation(year, month, "UTC", List.of(), new SquareMonthAggregator.Diag(),
                services, List.of(), List.of());
    }

    @Test
    @DisplayName("cash includes CASH and CASH-NOTE channels; card is the rest; total is their sum")
    void cashIncludesCashNotes() {
        // Past month so the window is the full month (deterministic, no 'today' dependency).
        List<AttributedService> may = List.of(
                svc("2026-05-03", "CARD", "100.00"),
                svc("2026-05-10", "CASH", "40.00"),
                svc("2026-05-12", "CASH-NOTE", "60.00")); // manual cash — the piece the old sum missed
        when(aggregator.aggregate(eq(2026), eq(5), any())).thenReturn(aggOf(2026, 5, may));
        when(aggregator.aggregate(eq(2026), eq(4), any()))
                .thenReturn(aggOf(2026, 4, List.of(svc("2026-04-05", "CARD", "80.00"))));
        when(forecaster.forecast(anyInt(), anyInt(), any(), any()))
                .thenReturn(new ForecastResult(new BigDecimal("200.00"), null, null, 0, 0));

        RevenuePulseDto p = service.pulse(2026, 5);

        assertThat(p.currentCard()).isEqualByComparingTo("100.00");
        assertThat(p.currentCash()).isEqualByComparingTo("100.00"); // 40 cash + 60 cash-note
        assertThat(p.currentGross()).isEqualByComparingTo("200.00");
        assertThat(p.priorCard()).isEqualByComparingTo("80.00");
        assertThat(p.priorCash()).isEqualByComparingTo("0.00");
        // Month lengths are surfaced so the UI can flag the mismatch (May 31 vs April 30).
        assertThat(p.currentMonthLength()).isEqualTo(31);
        assertThat(p.priorMonthLength()).isEqualTo(30);
        assertThat(p.asOfTime()).isNull(); // past month → whole-day comparison, no time cutoff
        // Only meaningful relative to "now" — a past-month view has no pace-vs-last-month comparison.
        assertThat(p.priorProjected()).isNull();
        assertThat(p.projectedDeltaPct()).isNull();
    }

    @Test
    @DisplayName("current month cuts both windows at the same time-of-day, to the minute")
    void currentMonthHonoursTimeOfDayCutoff() {
        // Fix 'now' to Aug 15 2026, 12:00 PM UTC. Both months are compared through day 15 at noon.
        Clock clock = Clock.fixed(Instant.parse("2026-08-15T12:00:00Z"), ZoneOffset.UTC);
        RevenuePulseService timed = new RevenuePulseService(squareClientProvider, forecaster, aggregator, salonConfig,
                currentBusinessContext, snapshots, manualAdjustments, clock);

        when(aggregator.aggregate(eq(2026), eq(8), any())).thenReturn(aggOf(2026, 8, List.of(
                svcAt("2026-08-03", "9:00 AM", "CARD", "50.00"),   // earlier day → counted
                svcAt("2026-08-15", "10:00 AM", "CARD", "100.00"), // today, before noon → counted
                svcAt("2026-08-15", "2:00 PM", "CARD", "500.00")))); // today, after noon → excluded
        when(aggregator.aggregate(eq(2026), eq(7), any())).thenReturn(aggOf(2026, 7, List.of(
                svcAt("2026-07-02", "8:00 AM", "CARD", "20.00"),   // earlier day → counted
                svcAt("2026-07-15", "11:00 AM", "CARD", "30.00"),  // matching day, before noon → counted
                svcAt("2026-07-15", "3:00 PM", "CARD", "999.00")))); // matching day, after noon → excluded
        when(forecaster.forecast(anyInt(), anyInt(), any(), any()))
                .thenReturn(new ForecastResult(new BigDecimal("300.00"), null, null, 0, 0));
        // The real historical record of what the app projected on July 15 — not recomputed.
        when(snapshots.findByBusinessIdAndSnapshotDate(1L, LocalDate.of(2026, 7, 15))).thenReturn(Optional.of(
                RevenueSnapshot.builder().mtdRevenue(new BigDecimal("100.00"))
                        .upcomingGross(new BigDecimal("50.00")).build()));

        RevenuePulseDto p = timed.pulse(2026, 8);

        assertThat(p.currentGross()).isEqualByComparingTo("150.00"); // 50 + 100, not the 2 PM 500
        assertThat(p.priorGross()).isEqualByComparingTo("50.00");    // 20 + 30, not the 3 PM 999
        assertThat(p.deltaPct()).isEqualByComparingTo("200.0");      // (150 − 50) / 50
        assertThat(p.asOfTime()).isEqualTo("12:00 PM");
        assertThat(p.currentEndDay()).isEqualTo(15);
        assertThat(p.priorEndDay()).isEqualTo(15);
        // July 15's stored snapshot: 100.00 MTD + 50.00 upcoming-gross = 150.00 projected then.
        assertThat(p.priorProjected()).isEqualByComparingTo("150.00");
        // (300.00 forecast mid − 150.00) / 150.00 * 100
        assertThat(p.projectedDeltaPct()).isEqualByComparingTo("100.0");
    }

    @Test
    @DisplayName("no snapshot exists for that date → no comparison shown, not a guess")
    void noComparisonWhenSnapshotMissing() {
        // e.g. before daily snapshotting started, or a genuine gap in the data.
        Clock clock = Clock.fixed(Instant.parse("2026-08-01T00:01:00Z"), ZoneOffset.UTC);
        RevenuePulseService timed = new RevenuePulseService(squareClientProvider, forecaster, aggregator, salonConfig,
                currentBusinessContext, snapshots, manualAdjustments, clock);

        when(aggregator.aggregate(eq(2026), eq(8), any())).thenReturn(aggOf(2026, 8, List.of()));
        when(aggregator.aggregate(eq(2026), eq(7), any())).thenReturn(aggOf(2026, 7, List.of()));
        when(forecaster.forecast(anyInt(), anyInt(), any(), any()))
                .thenReturn(new ForecastResult(new BigDecimal("50.00"), null, null, 0, 0));
        // snapshots.findByBusinessIdAndSnapshotDate(1L, ...) already stubbed to Optional.empty() in setUp().

        RevenuePulseDto p = timed.pulse(2026, 8);

        assertThat(p.priorProjected()).isNull();
        assertThat(p.projectedDeltaPct()).isNull();
    }

    @Test
    @DisplayName("Manual Adjustments are folded into card revenue, matching /owner/overview's Gross")
    void manualAdjustmentsFoldedIntoCard() {
        // Past month so the window is the full month (deterministic, no 'today' dependency).
        when(aggregator.aggregate(eq(2026), eq(7), any())).thenReturn(aggOf(2026, 7, List.of(
                svc("2026-07-03", "CARD", "100.00"))));
        when(aggregator.aggregate(eq(2026), eq(6), any())).thenReturn(aggOf(2026, 6, List.of()));
        // A redo credit + a refund, same shape as the real production data that exposed this gap.
        when(manualAdjustments.totalGrossThrough(LocalDate.of(2026, 7, 31))).thenReturn(new BigDecimal("192.80"));
        when(forecaster.forecast(anyInt(), anyInt(), any(), any()))
                .thenReturn(new ForecastResult(new BigDecimal("300.00"), null, null, 0, 0));

        RevenuePulseDto p = service.pulse(2026, 7);

        // 100.00 (Square) + 192.80 (manual adjustments) — previously this widget silently omitted
        // the adjustment, so its Gross diverged from /owner/overview's for the same month.
        assertThat(p.currentCard()).isEqualByComparingTo("292.80");
        assertThat(p.currentGross()).isEqualByComparingTo("292.80");
    }

    @Test
    @DisplayName("projection is split by the current period's card:cash ratio")
    void projectionSplitByCurrentMix() {
        // Current month mix 75% card / 25% cash → a $400 forecast splits 300 / 100.
        when(aggregator.aggregate(eq(2026), eq(5), any())).thenReturn(aggOf(2026, 5, List.of(
                svc("2026-05-03", "CARD", "150.00"),
                svc("2026-05-04", "CASH", "50.00"))));
        when(aggregator.aggregate(eq(2026), eq(4), any())).thenReturn(aggOf(2026, 4, List.of()));
        when(forecaster.forecast(anyInt(), anyInt(), any(), any()))
                .thenReturn(new ForecastResult(new BigDecimal("400.00"), null, null, 0, 0));

        RevenuePulseDto p = service.pulse(2026, 5);

        assertThat(p.projectedCard()).isEqualByComparingTo("300.00");
        assertThat(p.projectedCash()).isEqualByComparingTo("100.00");
    }
}
