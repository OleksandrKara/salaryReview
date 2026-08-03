package com.salonreview.square;

import com.salonreview.domain.Half;
import com.salonreview.domain.PayPeriod;
import com.salonreview.domain.PeriodEntry;
import com.salonreview.domain.Provider;
import com.salonreview.domain.SalonConfig;
import com.salonreview.repo.PayPeriodRepository;
import com.salonreview.repo.PeriodEntryRepository;
import com.salonreview.repo.SalonConfigRepository;
import com.salonreview.service.CommissionCalculator;
import com.salonreview.web.dto.OwnerOverviewDto.MonthSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** openspec design.md D11/D12: a statement-covered month sources its expenses/manager-labor
 * cost/provider payroll exclusively from the reconciliation, never adding the computed/manual
 * figure on top; a month without statement coverage is completely unaffected (proposal.md
 * Non-goals: forward-only, per-period). Kept separate from {@code OwnerOverviewServiceTest} so
 * that file's many pre-D11 tests don't all need to know this switch exists. */
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

    private OwnerOverviewService build(ExpenseImportService expenseImports, ExpenseService expenses, ManagerTimeService managerTime) {
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

        return new OwnerOverviewService(payPeriods, entries, new CommissionCalculator(), salonConfig,
                mock(SquareMonthAggregator.class), mock(RetentionAnalyticsService.class), manualAdjustments,
                expenses, managerTime, expenseImports);
    }

    @Test
    @DisplayName("A statement-covered month sources expenses/manager-labor/payroll exclusively from the reconciliation")
    void statementCoveredMonthSourcesExclusivelyFromReconciliation() {
        ExpenseImportService expenseImports = mock(ExpenseImportService.class);
        when(expenseImports.isPeriodStatementCovered(any(), any())).thenReturn(true);
        when(expenseImports.linkedExpenseEntryIds(any(), any())).thenReturn(List.of(500L, 501L));

        ExpenseService expenses = mock(ExpenseService.class);
        when(expenses.resolveStatementDerivedExpenseTotal(List.of(500L, 501L))).thenReturn(new BigDecimal("120.00"));
        when(expenses.resolveStatementDerivedManagerLaborTotal(List.of(500L, 501L))).thenReturn(new BigDecimal("60.00"));
        when(expenses.resolveStatementDerivedProviderPayrollTotal(List.of(500L, 501L))).thenReturn(new BigDecimal("400.00"));
        ManagerTimeService managerTime = mock(ManagerTimeService.class);

        OwnerOverviewService service = build(expenseImports, expenses, managerTime);
        MonthSummary jan = service.overview(2025, 1, 2025, 12).months().get(0);

        assertThat(jan.expenseTotal()).isEqualByComparingTo("120.00");
        assertThat(jan.managerLaborCost()).isEqualByComparingTo("60.00");
        // the formula would have computed 450 (45% of 1000) — the real reconciled 400 wins instead
        assertThat(jan.payrollCost()).isEqualByComparingTo("400.00");
        // net = 1000 - 400 - 120 - 60 = 420
        assertThat(jan.netRevenue()).isEqualByComparingTo("420.00");
        // the computed/manual paths must never be consulted for a statement-covered month
        verify(managerTime, never()).totalLaborCost(any(), any());
        verify(expenses, never()).resolveExpenseTotal(any(), any());
        verify(expenses, never()).resolveManagerLaborManualTotal(any(), any());
    }

    @Test
    @DisplayName("A month without statement coverage is completely unaffected")
    void nonCoveredMonthIsUnaffected() {
        ExpenseImportService expenseImports = mock(ExpenseImportService.class);
        when(expenseImports.isPeriodStatementCovered(any(), any())).thenReturn(false);

        ExpenseService expenses = mock(ExpenseService.class);
        when(expenses.resolveExpenseTotal(any(), any())).thenReturn(new BigDecimal("100.00"));
        ManagerTimeService managerTime = mock(ManagerTimeService.class);
        when(managerTime.totalLaborCost(any(), any())).thenReturn(new BigDecimal("75.00"));

        OwnerOverviewService service = build(expenseImports, expenses, managerTime);
        MonthSummary jan = service.overview(2025, 1, 2025, 12).months().get(0);

        assertThat(jan.expenseTotal()).isEqualByComparingTo("100.00");
        assertThat(jan.managerLaborCost()).isEqualByComparingTo("75.00");
        // formula payroll (45% of 1000 gross) is untouched
        assertThat(jan.payrollCost()).isEqualByComparingTo("450.00");
        verify(expenses, never()).resolveStatementDerivedExpenseTotal(any());
        verify(expenses, never()).resolveStatementDerivedManagerLaborTotal(any());
        verify(expenses, never()).resolveStatementDerivedProviderPayrollTotal(any());
    }
}
