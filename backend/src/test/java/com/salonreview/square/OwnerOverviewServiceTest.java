package com.salonreview.square;

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
import com.salonreview.web.dto.OwnerOverviewDto;
import com.salonreview.web.dto.OwnerOverviewDto.MonthSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OwnerOverviewServiceTest {

    private PayPeriodRepository payPeriods;
    private PeriodEntryRepository entries;
    private SalonConfigRepository salonConfig;
    private com.salonreview.config.CurrentBusinessContext currentBusinessContext;
    private SquareMonthAggregator aggregator;
    private SettlementPreviewService settlementPreview;
    private BankTransactionRepository bankTransactions;
    private OwnerOverviewService service;

    private static final SalonConfig CFG = SalonConfig.builder()
            .id(1)
            .ownerShortName("Owner")
            .tierServiceThreshold(25)
            .servicePriceCutoff(new BigDecimal("60.00"))
            .baseCommissionRate(new BigDecimal("0.4500"))
            .tierCommissionRate(new BigDecimal("0.5000"))
            .cardTipFeeRate(new BigDecimal("0.0350"))
            .build();

    private static Provider provider(long id, String name) {
        return Provider.builder()
                .id(id)
                .name(name)
                .displayName(name)
                .commissionRate(new BigDecimal("0.4500"))
                .cardTipFeeRate(new BigDecimal("0.0350"))
                .active(true)
                .build();
    }

    private static PayPeriod period(long id, int year, int month, Half half) {
        return PayPeriod.builder().id(id).year(year).month(month).half(half)
                .label(half + " " + month + "/" + year).build();
    }

    private static PeriodEntry entry(Provider p, PayPeriod pp,
                                     String card, String cash, String tips, int procs) {
        return PeriodEntry.builder()
                .provider(p)
                .payPeriod(pp)
                .cardTotal(new BigDecimal(card))
                .cashTotal(new BigDecimal(cash))
                .cardTips(new BigDecimal(tips))
                .adjustmentsAmount(BigDecimal.ZERO)
                .procedures(procs)
                .build();
    }

    @BeforeEach
    void setUp() {
        payPeriods  = mock(PayPeriodRepository.class);
        entries     = mock(PeriodEntryRepository.class);
        salonConfig = mock(SalonConfigRepository.class);
        aggregator  = mock(SquareMonthAggregator.class);
        // Unstubbed (returns null from preview()) by default — providerCompensationForMonth treats
        // that as "no Square data for this month" and falls back to the formula/PeriodEntry-computed
        // combined payroll figure, with cash comp defaulting to zero (see OwnerOverviewService's own
        // doc comment on that fallback). Individual tests can stub preview() to exercise the new
        // card/cash-split behavior explicitly.
        settlementPreview = mock(SettlementPreviewService.class);
        bankTransactions = mock(BankTransactionRepository.class);
        // Retention (client counts) isn't the focus here; an unstubbed mock yields no counts, so months
        // simply report 0 clients seen/returning — which these revenue/payroll assertions ignore.
        ManualAdjustmentService manualAdjustments = mock(ManualAdjustmentService.class);
        ExpenseService expenses = mock(ExpenseService.class);
        ManagerTimeService managerTime = mock(ManagerTimeService.class);
        ExpenseImportService expenseImports = mock(ExpenseImportService.class);
        // No statement-covered months by default — individual D11 tests can override.
        when(expenseImports.isPeriodStatementCovered(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(false);
        currentBusinessContext = mock(com.salonreview.config.CurrentBusinessContext.class);
        when(currentBusinessContext.id()).thenReturn(1L);
        service     = new OwnerOverviewService(payPeriods, entries, new CommissionCalculator(),
                salonConfig, aggregator, mock(RetentionAnalyticsService.class), manualAdjustments, expenses,
                managerTime, expenseImports, settlementPreview, bankTransactions, currentBusinessContext);

        when(salonConfig.findByBusinessId(1L)).thenReturn(Optional.of(CFG));
        // Default: no periods for any year (overridden per test)
        when(payPeriods.findAllByBusinessIdAndYearOrderByMonthAscHalfAsc(anyLong(), anyInt())).thenReturn(List.of());
        when(entries.findAllByPayPeriodId(anyLong())).thenReturn(List.of());
        // No manual adjustments by default — individual tests can override to exercise the fold-in.
        when(manualAdjustments.totalGrossForMonth(anyInt(), anyInt())).thenReturn(BigDecimal.ZERO);
        when(manualAdjustments.countedUnitDeltaForMonth(anyInt(), anyInt(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(0);
        // No expenses by default — individual tests can override to exercise net-revenue math.
        when(expenses.resolveExpenseTotal(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(BigDecimal.ZERO);
        when(expenses.resolveCashBusinessExpenseTotal(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(BigDecimal.ZERO);
        // No manager labor cost by default — individual tests can override to exercise the fold-in.
        when(managerTime.totalLaborCost(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(BigDecimal.ZERO);
    }

    /** A fresh {@link ExpenseImportService} mock with no statement-covered months — the D11 sourcing
     * switch is unit-tested separately (see {@code OwnerOverviewServiceD11Test}); every other test
     * in this class exercises pre-D11 behavior and shouldn't have to know it exists. */
    private static ExpenseImportService notStatementCovered() {
        ExpenseImportService mock = mock(ExpenseImportService.class);
        when(mock.isPeriodStatementCovered(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(false);
        return mock;
    }

    @Test
    @DisplayName("Settled months aggregate card + cash revenue correctly from PeriodEntry")
    void settledMonthsAggregateRevenue() {
        Provider anna = provider(1L, "Anna");
        PayPeriod jan1 = period(1L, 2025, 1, Half.FIRST);
        PayPeriod jan2 = period(2L, 2025, 1, Half.SECOND);

        when(payPeriods.findAllByBusinessIdAndYearOrderByMonthAscHalfAsc(1L, 2025))
                .thenReturn(List.of(jan1, jan2));
        when(entries.findAllByPayPeriodId(1L))
                .thenReturn(List.of(entry(anna, jan1, "500.00", "200.00", "50.00", 6)));
        when(entries.findAllByPayPeriodId(2L))
                .thenReturn(List.of(entry(anna, jan2, "600.00", "100.00", "30.00", 7)));

        OwnerOverviewDto dto = service.overview(2025, 1, 2025, 12);
        MonthSummary jan = dto.months().get(0); // month 1

        assertThat(jan.finalized()).isTrue();
        assertThat(jan.cardRevenue()).isEqualByComparingTo("1100.00");
        assertThat(jan.cashRevenue()).isEqualByComparingTo("300.00");
        assertThat(jan.grossRevenue()).isEqualByComparingTo("1400.00");
        assertThat(jan.tips()).isEqualByComparingTo("80.00");
        assertThat(jan.procedures()).isEqualTo(13);
    }

    @Test
    @DisplayName("netRevenue subtracts both payroll and resolved expenses from gross revenue")
    void netRevenueSubtractsPayrollAndExpenses() {
        Provider anna = provider(1L, "Anna");
        PayPeriod jan1 = period(1L, 2025, 1, Half.FIRST);
        when(payPeriods.findAllByBusinessIdAndYearOrderByMonthAscHalfAsc(1L, 2025)).thenReturn(List.of(jan1));
        // Card-only: gross = 1000, payroll = 1000 * 0.45 = 450
        when(entries.findAllByPayPeriodId(1L))
                .thenReturn(List.of(entry(anna, jan1, "1000.00", "0.00", "0.00", 10)));

        ExpenseService expenses = mock(ExpenseService.class);
        when(expenses.resolveExpenseTotal(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new BigDecimal("100.00"));
        when(expenses.resolveCashBusinessExpenseTotal(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(BigDecimal.ZERO);
        ManagerTimeService managerTime = mock(ManagerTimeService.class);
        when(managerTime.totalLaborCost(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(BigDecimal.ZERO);
        OwnerOverviewService serviceWithExpenses = new OwnerOverviewService(payPeriods, entries,
                new CommissionCalculator(), salonConfig, aggregator, mock(RetentionAnalyticsService.class),
                mock(ManualAdjustmentService.class), expenses, managerTime, notStatementCovered(),
                settlementPreview, bankTransactions, currentBusinessContext);

        MonthSummary jan = serviceWithExpenses.overview(2025, 1, 2025, 12).months().get(0);

        assertThat(jan.grossRevenue()).isEqualByComparingTo("1000.00");
        assertThat(jan.payrollCost()).isEqualByComparingTo("450.00");
        assertThat(jan.expenseTotal()).isEqualByComparingTo("100.00");
        // net = 1000 - 450 - 100 - 0 = 450
        assertThat(jan.netRevenue()).isEqualByComparingTo("450.00");
    }

    @Test
    @DisplayName("netRevenue also subtracts manager labor cost, preferring the real clocked total over the manual backfill")
    void netRevenueSubtractsManagerLaborCost() {
        Provider anna = provider(1L, "Anna");
        PayPeriod jan1 = period(1L, 2025, 1, Half.FIRST);
        when(payPeriods.findAllByBusinessIdAndYearOrderByMonthAscHalfAsc(1L, 2025)).thenReturn(List.of(jan1));
        when(entries.findAllByPayPeriodId(1L))
                .thenReturn(List.of(entry(anna, jan1, "1000.00", "0.00", "0.00", 10)));

        ExpenseService expenses = mock(ExpenseService.class);
        when(expenses.resolveExpenseTotal(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(BigDecimal.ZERO);
        when(expenses.resolveCashBusinessExpenseTotal(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(BigDecimal.ZERO);
        ManagerTimeService managerTime = mock(ManagerTimeService.class);
        when(managerTime.totalLaborCost(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new BigDecimal("75.00"));
        OwnerOverviewService serviceWithManagerTime = new OwnerOverviewService(payPeriods, entries,
                new CommissionCalculator(), salonConfig, aggregator, mock(RetentionAnalyticsService.class),
                mock(ManualAdjustmentService.class), expenses, managerTime, notStatementCovered(),
                settlementPreview, bankTransactions, currentBusinessContext);

        MonthSummary jan = serviceWithManagerTime.overview(2025, 1, 2025, 12).months().get(0);

        assertThat(jan.managerLaborCost()).isEqualByComparingTo("75.00");
        // net = 1000 - 450 - 0 - 75 = 475
        assertThat(jan.netRevenue()).isEqualByComparingTo("475.00");
        // the manual backfill must never be consulted when real clocked data exists for the month
        org.mockito.Mockito.verify(expenses, org.mockito.Mockito.never())
                .resolveManagerLaborManualTotal(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("netRevenue falls back to the manual manager-labor backfill when no clocked data exists for the month")
    void netRevenueFallsBackToManualManagerLaborForMonthsWithoutClockedData() {
        Provider anna = provider(1L, "Anna");
        PayPeriod jan1 = period(1L, 2025, 1, Half.FIRST);
        when(payPeriods.findAllByBusinessIdAndYearOrderByMonthAscHalfAsc(1L, 2025)).thenReturn(List.of(jan1));
        when(entries.findAllByPayPeriodId(1L))
                .thenReturn(List.of(entry(anna, jan1, "1000.00", "0.00", "0.00", 10)));

        ExpenseService expenses = mock(ExpenseService.class);
        when(expenses.resolveExpenseTotal(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(BigDecimal.ZERO);
        when(expenses.resolveCashBusinessExpenseTotal(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(BigDecimal.ZERO);
        when(expenses.resolveManagerLaborManualTotal(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new BigDecimal("50.00"));
        ManagerTimeService managerTime = mock(ManagerTimeService.class);
        when(managerTime.totalLaborCost(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(null); // no clocked shifts at all this month (before the feature existed)
        OwnerOverviewService serviceWithManualBackfill = new OwnerOverviewService(payPeriods, entries,
                new CommissionCalculator(), salonConfig, aggregator, mock(RetentionAnalyticsService.class),
                mock(ManualAdjustmentService.class), expenses, managerTime, notStatementCovered(),
                settlementPreview, bankTransactions, currentBusinessContext);

        MonthSummary jan = serviceWithManualBackfill.overview(2025, 1, 2025, 12).months().get(0);

        assertThat(jan.managerLaborCost()).isEqualByComparingTo("50.00");
        // net = 1000 - 450 - 0 - 50 = 500
        assertThat(jan.netRevenue()).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("Payroll % is ~45% for a base-rate provider with no adjustments")
    void payrollPctIsApprox45ForBaseRate() {
        Provider anna = provider(1L, "Anna");
        PayPeriod mar1 = period(3L, 2025, 3, Half.FIRST);

        when(payPeriods.findAllByBusinessIdAndYearOrderByMonthAscHalfAsc(1L, 2025))
                .thenReturn(List.of(mar1));
        // Card-only, no cash or tips — payroll = card * 0.45
        when(entries.findAllByPayPeriodId(3L))
                .thenReturn(List.of(entry(anna, mar1, "1000.00", "0.00", "0.00", 10)));

        MonthSummary mar = service.overview(2025, 1, 2025, 12).months().get(2); // month 3

        assertThat(mar.finalized()).isTrue();
        // zelle = 1000 * 0.45 = 450; cashToSalon = 0; payroll = 450; gross = 1000
        assertThat(mar.payrollCost()).isEqualByComparingTo("450.00");
        assertThat(mar.payrollPct()).isEqualByComparingTo("45.0");
    }

    @Test
    @DisplayName("Month with no entries returns nulls for revenue fields")
    void monthWithNoEntriesReturnsNulls() {
        // No periods for 2024 at all (default mock returns empty list)
        OwnerOverviewDto dto = service.overview(2024, 1, 2024, 12);

        for (MonthSummary m : dto.months()) {
            assertThat(m.grossRevenue()).isNull();
            assertThat(m.payrollPct()).isNull();
            assertThat(m.finalized()).isFalse();
        }
    }

    @Test
    @DisplayName("Future months have null revenue (no entries, not current month)")
    void futureMonthsHaveNullRevenue() {
        // Requesting next year — all months should be empty/null
        int futureYear = java.time.LocalDate.now().getYear() + 1;
        when(payPeriods.findAllByBusinessIdAndYearOrderByMonthAscHalfAsc(1L, futureYear)).thenReturn(List.of());

        OwnerOverviewDto dto = service.overview(futureYear, 1, futureYear, 12);
        assertThat(dto.months()).hasSize(12)
                .allSatisfy(m -> assertThat(m.grossRevenue()).isNull());
    }

    @Test
    @DisplayName("Provider YTD list is sorted descending by gross and covers settled months only")
    void providerYtdSortedByGross() {
        Provider anna = provider(1L, "Anna");
        Provider kate = provider(2L, "Kate");
        PayPeriod mar1 = period(3L, 2025, 3, Half.FIRST);

        when(payPeriods.findAllByBusinessIdAndYearOrderByMonthAscHalfAsc(1L, 2025)).thenReturn(List.of(mar1));
        when(entries.findAllByPayPeriodId(3L)).thenReturn(List.of(
                entry(anna, mar1, "800.00", "0.00", "0.00", 8),
                entry(kate, mar1, "1200.00", "0.00", "0.00", 12)));

        OwnerOverviewDto dto = service.overview(2025, 1, 2025, 12);
        assertThat(dto.providers()).hasSize(2);
        assertThat(dto.providers().get(0).name()).isEqualTo("Kate"); // higher gross first
        assertThat(dto.providers().get(1).name()).isEqualTo("Anna");
    }

    @Test
    @DisplayName("syncedAt is populated on the response")
    void syncedAtIsPopulated() {
        OwnerOverviewDto dto = service.overview(2025, 1, 2025, 12);

        assertThat(dto.syncedAt()).isNotNull();
        assertThat(java.time.Instant.parse(dto.syncedAt())).isNotNull(); // parses as a real ISO instant
    }

    @Test
    @DisplayName("30-day cache: identical range is served from cache, not recomputed from the DB")
    void identicalRangeIsCached() {
        when(payPeriods.findAllByBusinessIdAndYearOrderByMonthAscHalfAsc(1L, 2025)).thenReturn(List.of());

        service.overview(2025, 1, 2025, 12);
        service.overview(2025, 1, 2025, 12);

        // Only one real computation happened — the repository lookup for 2025 fired exactly once,
        // not twice, even though overview() was called twice with the same range.
        org.mockito.Mockito.verify(payPeriods, org.mockito.Mockito.times(1))
                .findAllByBusinessIdAndYearOrderByMonthAscHalfAsc(1L, 2025);
    }

    @Test
    @DisplayName("30-day cache: a different range is computed fresh, not served from another range's cache entry")
    void differentRangeIsNotServedFromCache() {
        // Years far enough apart that neither range's own prevPeriodTotals() lookup (fromYear - 1)
        // touches the other range's year — isolates "was this range's own cache entry reused" from
        // the unrelated prior-year-totals side query every overview() call also makes.
        when(payPeriods.findAllByBusinessIdAndYearOrderByMonthAscHalfAsc(1L, 2025)).thenReturn(List.of());
        when(payPeriods.findAllByBusinessIdAndYearOrderByMonthAscHalfAsc(1L, 2010)).thenReturn(List.of());

        service.overview(2025, 1, 2025, 12);
        service.overview(2010, 1, 2010, 12);

        org.mockito.Mockito.verify(payPeriods, org.mockito.Mockito.times(1)).findAllByBusinessIdAndYearOrderByMonthAscHalfAsc(1L, 2025);
        org.mockito.Mockito.verify(payPeriods, org.mockito.Mockito.times(1)).findAllByBusinessIdAndYearOrderByMonthAscHalfAsc(1L, 2010);
    }

    @Test
    @DisplayName("invalidateCache() forces the next call to recompute rather than serving the cached response")
    void invalidateCacheForcesRecompute() {
        when(payPeriods.findAllByBusinessIdAndYearOrderByMonthAscHalfAsc(1L, 2025)).thenReturn(List.of());

        service.overview(2025, 1, 2025, 12);
        service.invalidateCache();
        service.overview(2025, 1, 2025, 12);

        org.mockito.Mockito.verify(payPeriods, org.mockito.Mockito.times(2))
                .findAllByBusinessIdAndYearOrderByMonthAscHalfAsc(1L, 2025);
    }
}
