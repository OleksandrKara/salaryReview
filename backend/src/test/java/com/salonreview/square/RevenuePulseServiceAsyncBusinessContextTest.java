package com.salonreview.square;

import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.domain.SalonConfig;
import com.salonreview.repo.RevenueSnapshotRepository;
import com.salonreview.repo.SalonConfigRepository;
import com.salonreview.square.SquareMonthAggregator.MonthAggregation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression test for a real production incident: the /reports "Revenue pulse" block vanished with a
 * 500 ({@code CurrentBusinessContext was never populated for this thread}). {@code pulse()} runs its
 * two months' {@code mtdSplit()} calls via {@code CompletableFuture.supplyAsync} — each hands off to a
 * different thread-pool thread. {@code mtdSplit()} reaches
 * {@code ManualAdjustmentService.totalGrossThrough()} -> {@code CurrentBusinessContext.id()}, and a
 * ThreadLocal set on the calling (request) thread does not carry over to that worker thread. A mocked
 * {@link CurrentBusinessContext} would never have caught this (mocks have no real thread semantics) —
 * this uses a genuine instance, same approach as {@code OwnerOverviewServiceAsyncBusinessContextTest}
 * for the original occurrence of this bug class.
 */
class RevenuePulseServiceAsyncBusinessContextTest {

    @Test
    @DisplayName("the async mtdSplit() calls see the same business id as the calling thread, not an unpopulated context")
    void asyncMtdSplitSeesCorrectBusinessId() {
        var realContext = new CurrentBusinessContext();

        List<Long> businessIdsSeenOnWorkerThreads = Collections.synchronizedList(new java.util.ArrayList<>());

        ManualAdjustmentService manualAdjustments = mock(ManualAdjustmentService.class);
        when(manualAdjustments.totalGrossThrough(any())).thenAnswer(inv -> {
            // Simulates what the real totalGrossThrough() -> CurrentBusinessContext.id() chain does:
            // read the business id off whatever thread this executes on. If the ThreadLocal didn't
            // propagate, id() throws here exactly like it did in the real incident.
            businessIdsSeenOnWorkerThreads.add(realContext.id());
            return BigDecimal.ZERO;
        });

        SquareClient square = mock(SquareClient.class);
        when(square.locationTimeZone()).thenReturn("UTC");
        when(square.bookings(any(), any())).thenReturn(List.of());

        SquareMonthAggregator aggregator = mock(SquareMonthAggregator.class);
        when(aggregator.aggregate(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenReturn(new MonthAggregation(2026, 1, "UTC", List.of(), new SquareMonthAggregator.Diag(),
                        List.of(), List.of(), List.of()));

        SalonConfigRepository salonConfig = mock(SalonConfigRepository.class);
        when(salonConfig.findByBusinessId(42L)).thenReturn(java.util.Optional.of(
                SalonConfig.builder().id(1).businessId(42L).ownerShortName("o")
                        .servicePriceCutoff(new BigDecimal("60.00"))
                        .baseCommissionRate(new BigDecimal("0.45")).tierCommissionRate(new BigDecimal("0.50"))
                        .cardTipFeeRate(new BigDecimal("0.035")).tierServiceThreshold(60).build()));

        RevenueForecastService forecaster = mock(RevenueForecastService.class);
        when(forecaster.forecast(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(), any(), any()))
                .thenReturn(new ForecastResult(new BigDecimal("100.00"), null, null, 0, 0));

        RevenueSnapshotRepository snapshots = mock(RevenueSnapshotRepository.class);
        when(snapshots.findByBusinessIdAndSnapshotDate(eq(42L), any())).thenReturn(java.util.Optional.empty());

        RevenuePulseService service = new RevenuePulseService(square, forecaster, aggregator, salonConfig,
                realContext, snapshots, manualAdjustments);

        // A past month (not the current month) so both mtdSplit() calls run through the full window —
        // mirrors the real request filter: sets the context for the duration, exactly as
        // CurrentBusinessContextFilter does for a real HTTP request.
        realContext.runAs(42L, () -> service.pulse(2026, 1));

        assertThat(businessIdsSeenOnWorkerThreads)
                .as("both worker threads running mtdSplit() must see business 42, not throw")
                .containsExactly(42L, 42L);
    }
}
