package com.salonreview.square;

import com.salonreview.commission.CommissionConfig;
import com.salonreview.commission.HalfSettlement;
import com.salonreview.commission.Stage;
import com.salonreview.domain.Half;
import com.salonreview.domain.PayPeriod;
import com.salonreview.domain.PeriodEntry;
import com.salonreview.domain.Provider;
import com.salonreview.domain.SalonConfig;
import com.salonreview.repo.BankTransactionRepository;
import com.salonreview.repo.PayPeriodRepository;
import com.salonreview.repo.PeriodEntryRepository;
import com.salonreview.repo.SalonConfigRepository;
import com.salonreview.service.CommissionCalculator;
import com.salonreview.web.dto.OwnerOverviewDto.MonthSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** openspec design.md D11: a statement-covered month sources its expenses/manager-labor cost
 * exclusively from the reconciliation, never adding the computed/manual figure on top; a month
 * without statement coverage is completely unaffected (proposal.md Non-goals: forward-only,
 * per-period). Provider payroll/cash compensation is sourced from {@link SettlementPreviewService}
 * uniformly for every month regardless of statement coverage (see the P&L redesign) — bank-
 * categorized PROVIDER_PAYROLL is never consulted for that figure, which this file's first test
 * now asserts directly (test scenario 8: no double-counting between the Salary Report and a bank
 * settlement transaction representing the same expense). Kept separate from
 * {@code OwnerOverviewServiceTest} so that file's many pre-D11 tests don't all need to know this
 * switch exists. */
class OwnerOverviewServiceD11Test {

    private static final SalonConfig CFG = SalonConfig.builder().id(1).baseCommissionRate(new BigDecimal("0.45"))
            .cardTipFeeRate(new BigDecimal("0.10")).build();

    private static Provider provider(long id, String name) {
        return Provider.builder().id(id).name(name).displayName(name).commissionRate(new BigDecimal("0.45"))
                .cardTipFeeRate(new BigDecimal("0.10")).active(true).build();
    }

    private static PeriodEntry entry(Provider p, PayPeriod period, String card, String cash, String tips, int services) {
        return PeriodEntry.builder().provider(p).payPeriod(period).cardTotal(new BigDecimal(card))
                .cashTotal(new BigDecimal(cash)).cardTips(new BigDecimal(tips))
                .adjustmentsAmount(BigDecimal.ZERO).procedures(services).build();
    }

    /** A one-provider {@link SettlementPreviewService.SettlementPreview} fixture whose
     * monthZelleToProvider/derived-cash figures are exactly {@code card}/{@code cash} — mirrors how
     * OwnerOverviewService.providerCompensationForMonth sums across providers(). */
    private static SettlementPreviewService.SettlementPreview preview(BigDecimal card, BigDecimal cash) {
        // cashCollected = cash, monthCashToSalon = 0, so the derived cash comp
        // (cashCollected - monthCashToSalon) providerCompensationForMonth computes is exactly `cash`.
        HalfSettlement half = new HalfSettlement(Half.FIRST, Stage.PROVISIONAL_FIRST_HALF, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, cash, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, card, BigDecimal.ZERO);
        HalfSettlement empty = new HalfSettlement(Half.SECOND, Stage.FINAL_MONTH_CLOSE, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        SettlementPreviewService.ProviderPayout payout = new SettlementPreviewService.ProviderPayout(
                1L, "Anna", 0, false, false, false, half, empty, card, BigDecimal.ZERO,
                null, null, null, null, 0, 0, 0, 0, 0, 0);
        CommissionConfig config = new CommissionConfig(60, new BigDecimal("0.45"), new BigDecimal("0.50"),
                new BigDecimal("0.10"));
        return new SettlementPreviewService.SettlementPreview(2025, 1, "America/Los_Angeles", config,
                BigDecimal.ZERO, List.of(payout), new SquareMonthAggregator.Diag(), "2025-01-31T00:00:00Z");
    }

    private OwnerOverviewService build(ExpenseImportService expenseImports, ExpenseService expenses,
                                        ManagerTimeService managerTime, SettlementPreviewService settlementPreview) {
        PayPeriodRepository payPeriods = mock(PayPeriodRepository.class);
        PeriodEntryRepository entries = mock(PeriodEntryRepository.class);
        SalonConfigRepository salonConfig = mock(SalonConfigRepository.class);
        when(salonConfig.findById(1)).thenReturn(Optional.of(CFG));

        Provider anna = provider(1L, "Anna");
        PayPeriod jan1 = PayPeriod.builder().id(1L).year(2025).month(1).half(Half.FIRST).label("First 1/2025").build();
        when(payPeriods.findAllByYearOrderByMonthAscHalfAsc(2025)).thenReturn(List.of(jan1));
        when(entries.findAllByPayPeriodId(1L)).thenReturn(List.of(entry(anna, jan1, "1000.00", "0.00", "0.00", 10)));

        ManualAdjustmentService manualAdjustments = mock(ManualAdjustmentService.class);
        when(manualAdjustments.totalGrossForMonth(anyInt(), anyInt())).thenReturn(BigDecimal.ZERO);
        when(manualAdjustments.countedUnitDeltaForMonth(anyInt(), anyInt(), any())).thenReturn(0);

        BankTransactionRepository bankTransactions = mock(BankTransactionRepository.class);
        when(bankTransactions.sumOwnerDrawsForCompletedImportsOverlapping(anyList(), any(), any()))
                .thenReturn(BigDecimal.ZERO);

        return new OwnerOverviewService(payPeriods, entries, new CommissionCalculator(), salonConfig,
                mock(SquareMonthAggregator.class), mock(RetentionAnalyticsService.class), manualAdjustments,
                expenses, managerTime, expenseImports, settlementPreview, bankTransactions);
    }

    @Test
    @DisplayName("A statement-covered month sources expenses/manager-labor from the reconciliation, "
            + "and provider payroll exclusively from SettlementPreviewService, never bank PROVIDER_PAYROLL")
    void statementCoveredMonthSourcesExclusivelyFromReconciliationAndSettlementPreview() {
        ExpenseImportService expenseImports = mock(ExpenseImportService.class);
        when(expenseImports.isPeriodStatementCovered(any(), any())).thenReturn(true);
        when(expenseImports.linkedExpenseEntryIds(any(), any())).thenReturn(List.of(500L, 501L));

        ExpenseService expenses = mock(ExpenseService.class);
        when(expenses.resolveStatementDerivedExpenseTotal(List.of(500L, 501L))).thenReturn(new BigDecimal("120.00"));
        when(expenses.resolveStatementDerivedManagerLaborTotal(List.of(500L, 501L))).thenReturn(new BigDecimal("60.00"));
        when(expenses.resolveCashBusinessExpenseTotal(any(), any())).thenReturn(BigDecimal.ZERO);
        // A bank PROVIDER_PAYROLL entry exists (real Zelle transfer for card commission) alongside a
        // SettlementPreview — the P&L must source payroll from the preview only, proving no
        // double-count between the two (test scenario 8).
        when(expenses.resolveStatementDerivedProviderPayrollTotal(List.of(500L, 501L))).thenReturn(new BigDecimal("400.00"));
        ManagerTimeService managerTime = mock(ManagerTimeService.class);
        SettlementPreviewService settlementPreview = mock(SettlementPreviewService.class);
        when(settlementPreview.preview(2025, 1)).thenReturn(preview(new BigDecimal("450.00"), new BigDecimal("30.00")));

        OwnerOverviewService service = build(expenseImports, expenses, managerTime, settlementPreview);
        MonthSummary jan = service.overview(2025, 1, 2025, 12).months().get(0);

        assertThat(jan.expenseTotal()).isEqualByComparingTo("120.00");
        assertThat(jan.managerLaborCost()).isEqualByComparingTo("60.00");
        // sourced from SettlementPreviewService (450.00), not the bank-derived 400.00
        assertThat(jan.payrollCost()).isEqualByComparingTo("450.00");
        assertThat(jan.cashProviderCompensation()).isEqualByComparingTo("30.00");
        // net = 1000 - 450 - 30 - 120 - 0 - 60 = 340
        assertThat(jan.netRevenue()).isEqualByComparingTo("340.00");
        assertThat(jan.statementCovered()).isTrue();
        // managerTime is checked first (returns null here — no clocked data, an unstubbed mock's
        // default), so the reconciliation figure is only reached because that check came up empty —
        // see managerLaborCostForMonth's real-work-timing-wins precedence.
        verify(managerTime).totalLaborCost(any(), any());
        verify(expenses, never()).resolveExpenseTotal(any(), any());
        verify(expenses, never()).resolveManagerLaborManualTotal(any(), any());
        // the bank-derived provider-payroll figure is never used to compute the P&L's payroll line
        verify(expenses, never()).resolveStatementDerivedProviderPayrollTotal(any());
    }

    @Test
    @DisplayName("A statement-covered month with real clocked manager time prefers that over the "
            + "reconciliation's linked MANAGER_TIME entries — the bug this test guards: a manager's July "
            + "labor cost silently read as $0 because that month's pay disbursement wasn't bank-categorized "
            + "MANAGER_TIME within the statement, even though the real clocked cost was nonzero")
    void statementCoveredMonthPrefersRealClockedManagerTimeOverReconciliation() {
        ExpenseImportService expenseImports = mock(ExpenseImportService.class);
        when(expenseImports.isPeriodStatementCovered(any(), any())).thenReturn(true);
        when(expenseImports.linkedExpenseEntryIds(any(), any())).thenReturn(List.of(500L, 501L));

        ExpenseService expenses = mock(ExpenseService.class);
        when(expenses.resolveStatementDerivedExpenseTotal(List.of(500L, 501L))).thenReturn(new BigDecimal("120.00"));
        // The reconciliation has no linked MANAGER_TIME entry this month (e.g. pay disbursed the
        // following month) — if this were still consulted it would silently read as zero.
        when(expenses.resolveStatementDerivedManagerLaborTotal(List.of(500L, 501L))).thenReturn(BigDecimal.ZERO);
        when(expenses.resolveCashBusinessExpenseTotal(any(), any())).thenReturn(BigDecimal.ZERO);
        ManagerTimeService managerTime = mock(ManagerTimeService.class);
        when(managerTime.totalLaborCost(any(), any())).thenReturn(new BigDecimal("2105.33"));
        SettlementPreviewService settlementPreview = mock(SettlementPreviewService.class);
        when(settlementPreview.preview(2025, 1)).thenReturn(preview(new BigDecimal("450.00"), new BigDecimal("30.00")));

        OwnerOverviewService service = build(expenseImports, expenses, managerTime, settlementPreview);
        MonthSummary jan = service.overview(2025, 1, 2025, 12).months().get(0);

        assertThat(jan.managerLaborCost()).isEqualByComparingTo("2105.33");
        // net = 1000 - 450 - 30 - 120 - 0 - 2105.33 = -1705.33
        assertThat(jan.netRevenue()).isEqualByComparingTo("-1705.33");
        verify(expenses, never()).resolveStatementDerivedManagerLaborTotal(any());
    }

    @Test
    @DisplayName("A month without statement coverage is completely unaffected for expenses/manager-labor; "
            + "payroll still sources from SettlementPreviewService")
    void nonCoveredMonthIsUnaffected() {
        ExpenseImportService expenseImports = mock(ExpenseImportService.class);
        when(expenseImports.isPeriodStatementCovered(any(), any())).thenReturn(false);

        ExpenseService expenses = mock(ExpenseService.class);
        when(expenses.resolveExpenseTotal(any(), any())).thenReturn(new BigDecimal("100.00"));
        when(expenses.resolveCashBusinessExpenseTotal(any(), any())).thenReturn(BigDecimal.ZERO);
        when(expenses.resolvePersonalTotal(any(), any())).thenReturn(BigDecimal.ZERO);
        ManagerTimeService managerTime = mock(ManagerTimeService.class);
        when(managerTime.totalLaborCost(any(), any())).thenReturn(new BigDecimal("75.00"));
        SettlementPreviewService settlementPreview = mock(SettlementPreviewService.class);
        when(settlementPreview.preview(2025, 1)).thenThrow(new RuntimeException("no Square data for this legacy month"));

        OwnerOverviewService service = build(expenseImports, expenses, managerTime, settlementPreview);
        MonthSummary jan = service.overview(2025, 1, 2025, 12).months().get(0);

        assertThat(jan.expenseTotal()).isEqualByComparingTo("100.00");
        assertThat(jan.managerLaborCost()).isEqualByComparingTo("75.00");
        // SettlementPreviewService failed for this month, so payroll falls back to the
        // formula/PeriodEntry-computed combined figure (45% of 1000 gross) and cash comp defaults to
        // zero rather than double-counting the cash share already folded into that combined figure.
        assertThat(jan.payrollCost()).isEqualByComparingTo("450.00");
        assertThat(jan.cashProviderCompensation()).isEqualByComparingTo("0.00");
        assertThat(jan.statementCovered()).isFalse();
        verify(expenses, never()).resolveStatementDerivedExpenseTotal(any());
        verify(expenses, never()).resolveStatementDerivedManagerLaborTotal(any());
        verify(expenses, never()).resolveStatementDerivedProviderPayrollTotal(any());
    }
}
