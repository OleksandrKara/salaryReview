package com.salonreview.square;

import com.salonreview.commission.CommissionConfig;
import com.salonreview.commission.HalfInput;
import com.salonreview.commission.HalfSettlement;
import com.salonreview.commission.Stage;
import com.salonreview.domain.Half;
import com.salonreview.domain.SalonConfig;
import com.salonreview.repo.BankTransactionRepository;
import com.salonreview.repo.PayPeriodRepository;
import com.salonreview.repo.PeriodEntryRepository;
import com.salonreview.repo.SalonConfigRepository;
import com.salonreview.service.CommissionCalculator;
import com.salonreview.square.SquareMonthAggregator.AttributedService;
import com.salonreview.square.SquareMonthAggregator.MonthAggregation;
import com.salonreview.square.SquareMonthAggregator.ProviderMonth;
import com.salonreview.web.dto.OwnerOverviewDto.MonthSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Owner comps must not count as Gross Revenue / Net Profit on the live-month ({@code fromSquare()})
 * path — see {@link SquareMonthAggregator}'s own doc for why they're folded into {@code cardRevenue}
 * in the first place (so the provider still gets paid), and {@link OwnerCompAggregatorTest} for the
 * real production booking this mirrors: Anna Comegys did a $99 service for owner-customer Anna Kara,
 * no payment taken. Found live 2026-08-22 — this dashboard's Gross Revenue (and therefore Net Profit)
 * was crediting that $99 as if the salon had actually collected it.
 *
 * <p>Setup mirrors {@code OwnerOverviewServicePAndLTest} (see its own doc for why {@code fromSquare()}
 * is exercised directly rather than the legacy {@code fromEntries()} path).
 */
class OwnerOverviewServiceOwnerCompTest {

    private static final SalonConfig CFG = SalonConfig.builder().id(1)
            .baseCommissionRate(new BigDecimal("0.45")).cardTipFeeRate(new BigDecimal("0.10")).build();

    private SquareMonthAggregator aggregator;
    private SettlementPreviewService settlementPreview;
    private OwnerOverviewService service;

    @BeforeEach
    void setUp() {
        PayPeriodRepository payPeriods = mock(PayPeriodRepository.class);
        PeriodEntryRepository entries = mock(PeriodEntryRepository.class);
        SalonConfigRepository salonConfig = mock(SalonConfigRepository.class);
        com.salonreview.config.CurrentBusinessContext currentBusinessContext =
                mock(com.salonreview.config.CurrentBusinessContext.class);
        when(currentBusinessContext.id()).thenReturn(1L);
        org.mockito.Mockito.doAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return null;
        }).when(currentBusinessContext).runAs(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
        when(salonConfig.findByBusinessId(1L)).thenReturn(Optional.of(CFG));
        when(payPeriods.findAllByBusinessIdAndYearOrderByMonthAscHalfAsc(eq(1L), anyInt())).thenReturn(List.of());

        aggregator = mock(SquareMonthAggregator.class);
        RetentionAnalyticsService retention = mock(RetentionAnalyticsService.class);
        ManualAdjustmentService manualAdjustments = mock(ManualAdjustmentService.class);
        when(manualAdjustments.totalGrossForMonth(anyInt(), anyInt())).thenReturn(BigDecimal.ZERO);
        when(manualAdjustments.countedUnitDeltaForMonth(anyInt(), anyInt(), any())).thenReturn(0);

        ExpenseService expenses = mock(ExpenseService.class);
        when(expenses.resolveExpenseTotal(any(), any())).thenReturn(BigDecimal.ZERO);
        when(expenses.resolveCashBusinessExpenseTotal(any(), any())).thenReturn(BigDecimal.ZERO);
        when(expenses.resolvePersonalTotal(any(), any())).thenReturn(BigDecimal.ZERO);
        when(expenses.resolveExpenseBreakdownByCategory(any(), any())).thenReturn(Map.of());
        when(expenses.resolveCashBusinessExpenseBreakdownByCategory(any(), any())).thenReturn(Map.of());
        when(expenses.resolvePersonalBreakdownByCategory(any(), any())).thenReturn(Map.of());

        ManagerTimeService managerTime = mock(ManagerTimeService.class);
        when(managerTime.totalLaborCost(any(), any())).thenReturn(BigDecimal.ZERO);

        ExpenseImportService expenseImports = mock(ExpenseImportService.class);
        when(expenseImports.isPeriodStatementCovered(any(), any())).thenReturn(false);

        settlementPreview = mock(SettlementPreviewService.class);
        BankTransactionRepository bankTransactions = mock(BankTransactionRepository.class);
        when(bankTransactions.sumOwnerDrawsForCompletedImportsOverlapping(anyList(), any(), any()))
                .thenReturn(BigDecimal.ZERO);

        service = new OwnerOverviewService(payPeriods, entries, new CommissionCalculator(), salonConfig,
                aggregator, retention, manualAdjustments, expenses, managerTime, expenseImports,
                settlementPreview, bankTransactions, currentBusinessContext);
    }

    /** One provider, one month: a real $1000 card sale plus a $99 owner comp for Anna Kara (Anna
     * Comegys did the work) — the same shape {@link SquareMonthAggregator} actually produces:
     * cardRevenue/countedServices already include the comp (so payroll pays it out), and the comp
     * also appears as its own "COMP"-channel line in {@code services()}. */
    private void stubSquareMonthWithOwnerComp(int year, int month) {
        HalfInput half = new HalfInput(11, new BigDecimal("1099.00"), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        ProviderMonth pm = new ProviderMonth("sq-1", "Anna Comegys", half, HalfInput.empty());
        AttributedService compLine = new AttributedService("sq-1", "Anna Comegys",
                year + "-" + String.format("%02d", month) + "-18", "SECOND", "Nail Artist",
                new BigDecimal("99.00"), BigDecimal.ZERO, new BigDecimal("99.00"), BigDecimal.ZERO,
                true, 1, 1, false, "COMP", "3:00 PM", "bk-comp", "CUST-ANNA-KARA", "Anna Kara");
        MonthAggregation agg = new MonthAggregation(year, month, "America/Los_Angeles", List.of(pm),
                new SquareMonthAggregator.Diag(), List.of(compLine), List.of(), List.of());
        when(aggregator.aggregate(year, month, CFG.getServicePriceCutoff())).thenReturn(agg);
    }

    private static SettlementPreviewService.SettlementPreview preview(int year, int month, BigDecimal card) {
        HalfSettlement half = new HalfSettlement(Half.FIRST, Stage.PROVISIONAL_FIRST_HALF, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, card, BigDecimal.ZERO);
        HalfSettlement empty = new HalfSettlement(Half.SECOND, Stage.FINAL_MONTH_CLOSE, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        SettlementPreviewService.ProviderPayout payout = new SettlementPreviewService.ProviderPayout(
                1L, "Anna Comegys", 0, false, false, false, half, empty, card, BigDecimal.ZERO,
                null, null, null, null, 0, 0, 0, 0, 0, 0);
        CommissionConfig config = new CommissionConfig(60, new BigDecimal("0.45"), new BigDecimal("0.50"),
                new BigDecimal("0.10"), true);
        return new SettlementPreviewService.SettlementPreview(year, month, "America/Los_Angeles", config,
                BigDecimal.ZERO, List.of(payout), new SquareMonthAggregator.Diag(), year + "-" + month + "-01T00:00:00Z");
    }

    private MonthSummary monthFor(int year, int month) {
        return service.overview(year, month, year, month).months().get(0);
    }

    @Test
    @DisplayName("Anna Kara's $99 owner comp is excluded from Gross Revenue and Net Profit, but Anna "
            + "Comegys's payroll still includes it — the provider still gets paid, the salon just "
            + "doesn't book money it never collected")
    void ownerCompExcludedFromGrossButNotPayroll() {
        stubSquareMonthWithOwnerComp(2026, 3);
        // Payroll comes entirely from SettlementPreviewService, independent of the fix below — Anna
        // Comegys's $494.55 commission (45% of the full $1099, comp included) is exactly what it
        // already was before this fix, proving payroll is untouched.
        when(settlementPreview.preview(2026, 3)).thenReturn(preview(2026, 3, new BigDecimal("494.55")));

        MonthSummary mar = monthFor(2026, 3);

        // Gross Revenue: $1099 aggregated card revenue minus the $99 comp = $1000 real revenue.
        assertThat(mar.grossRevenue()).isEqualByComparingTo("1000.00");
        // Payroll: unaffected by this fix — still the full comp-inclusive commission.
        assertThat(mar.payrollCost()).isEqualByComparingTo("494.55");
        // Net = 1000 (real revenue) - 494.55 (real payroll, comp included) = 505.45 — before this
        // fix this read 1099 - 494.55 = 604.45, overstating profit by the comp's own value.
        assertThat(mar.netRevenue()).isEqualByComparingTo("505.45");
    }
}
