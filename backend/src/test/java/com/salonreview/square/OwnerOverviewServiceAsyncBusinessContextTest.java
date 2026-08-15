package com.salonreview.square;

import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.commission.CommissionConfig;
import com.salonreview.repo.*;
import com.salonreview.service.CommissionCalculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression test for a real bug found while shipping business_id scoping (V86): {@code overview()}
 * fetches live (not-yet-settled) months concurrently via {@code CompletableFuture.runAsync}, which
 * hands each month's {@code fromSquare()} call to a different thread-pool thread. A
 * {@link CurrentBusinessContext} populated on the calling thread does not carry over to that worker
 * thread — the ThreadLocal read there is simply empty. Before the fix, this meant every live-month
 * fetch's inner {@code settlementPreview.preview()} call threw {@code IllegalStateException}, which
 * {@code providerCompensationForMonth}'s own {@code catch (RuntimeException e)} silently swallowed
 * into a zeroed-out cash figure — no error surfaced anywhere, only a wrong number. This was caught by
 * a live regression-snapshot diff against real production data, not by any unit test, which is
 * exactly why this test exists now: a mocked {@link CurrentBusinessContext} would never have caught
 * it (mocks have no real thread semantics) — this uses a genuine instance.
 */
class OwnerOverviewServiceAsyncBusinessContextTest {

    private static final CommissionConfig CFG_COMMISSION =
            new CommissionConfig(60, new BigDecimal("0.4500"), new BigDecimal("0.5000"), new BigDecimal("0.0350"), true);

    @Test
    @DisplayName("the async live-month fetch path sees the same business id as the calling thread, not an unpopulated context")
    void asyncLiveMonthFetchSeesCorrectBusinessId() {
        var realContext = new CurrentBusinessContext();

        List<Long> businessIdsSeenOnWorkerThreads = Collections.synchronizedList(new java.util.ArrayList<>());

        SettlementPreviewService settlementPreview = mock(SettlementPreviewService.class);
        when(settlementPreview.preview(anyInt(), anyInt())).thenAnswer(inv -> {
            // Simulates what the real settlementPreview.preview() -> salonConfig.findByBusinessId(...)
            // chain does: read the business id off whatever thread this executes on. If the ThreadLocal
            // didn't propagate, id() throws here exactly like it did in the real incident.
            businessIdsSeenOnWorkerThreads.add(realContext.id());
            return new SettlementPreviewService.SettlementPreview(
                    (Integer) inv.getArgument(0), (Integer) inv.getArgument(1), "UTC", CFG_COMMISSION,
                    new BigDecimal("60.00"), List.of(), new SquareMonthAggregator.Diag(), "2026-01-01T00:00:00Z");
        });

        SalonConfigRepository salonConfig = mock(SalonConfigRepository.class);
        when(salonConfig.findByBusinessId(42L)).thenReturn(java.util.Optional.of(
                com.salonreview.domain.SalonConfig.builder().id(1).businessId(42L).ownerShortName("o")
                        .servicePriceCutoff(new BigDecimal("60.00"))
                        .baseCommissionRate(new BigDecimal("0.45")).tierCommissionRate(new BigDecimal("0.50"))
                        .cardTipFeeRate(new BigDecimal("0.035")).tierServiceThreshold(60).build()));

        PayPeriodRepository payPeriods = mock(PayPeriodRepository.class);
        when(payPeriods.findAllByBusinessIdAndYearOrderByMonthAscHalfAsc(eq(42L), anyInt())).thenReturn(List.of());
        PeriodEntryRepository entries = mock(PeriodEntryRepository.class);
        BankTransactionRepository bankTransactions = mock(BankTransactionRepository.class);
        when(bankTransactions.sumOwnerDrawsForCompletedImportsOverlapping(any(), any(), any()))
                .thenReturn(BigDecimal.ZERO);
        com.salonreview.square.ManualAdjustmentService manualAdjustments = mock(ManualAdjustmentService.class);
        when(manualAdjustments.totalGrossForMonth(anyInt(), anyInt())).thenReturn(BigDecimal.ZERO);
        when(manualAdjustments.countedUnitDeltaForMonth(anyInt(), anyInt(), any())).thenReturn(0);
        ExpenseService expenses = mock(ExpenseService.class);
        ManagerTimeService managerTime = mock(ManagerTimeService.class);
        ExpenseImportService expenseImports = mock(ExpenseImportService.class);
        SquareMonthAggregator aggregator = mock(SquareMonthAggregator.class);
        // fromSquare() needs a real (non-null) aggregation to get past its own null-check before it
        // ever reaches providerCompensationForMonth() — an unstubbed mock returns null here, which
        // would throw a NullPointerException on agg.providers() and get swallowed by fromSquare's own
        // try/catch before the code under test (the async businessId propagation) is even reached.
        when(aggregator.aggregate(anyInt(), anyInt(), any())).thenReturn(
                new SquareMonthAggregator.MonthAggregation(2026, 1, "UTC", List.of(),
                        new SquareMonthAggregator.Diag(), List.of(), List.of(), List.of()));

        OwnerOverviewService service = new OwnerOverviewService(payPeriods, entries,
                new CommissionCalculator(), salonConfig, aggregator, mock(RetentionAnalyticsService.class),
                manualAdjustments, expenses, managerTime, expenseImports, settlementPreview, bankTransactions,
                realContext);

        // A one-month range with no PayPeriod data forces the live/async fromSquare() path — a month
        // in the past (not future) so it's eligible, per computeOverview's own isFuture() filter.
        // Mirrors the real request filter: sets the context for the duration, exactly as
        // CurrentBusinessContextFilter does for a real HTTP request.
        LocalDate aMonthAgo = LocalDate.now().minusMonths(1);
        realContext.runAs(42L, () -> service.overview(aMonthAgo.getYear(), aMonthAgo.getMonthValue(),
                aMonthAgo.getYear(), aMonthAgo.getMonthValue()));

        assertThat(businessIdsSeenOnWorkerThreads)
                .as("the worker thread running fromSquare() must see business 42, not throw")
                .containsExactly(42L);
    }
}
