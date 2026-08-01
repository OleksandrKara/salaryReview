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
    private SquareMonthAggregator aggregator;
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
        // Retention (client counts) isn't the focus here; an unstubbed mock yields no counts, so months
        // simply report 0 clients seen/returning — which these revenue/payroll assertions ignore.
        ManualAdjustmentService manualAdjustments = mock(ManualAdjustmentService.class);
        ExpenseService expenses = mock(ExpenseService.class);
        service     = new OwnerOverviewService(payPeriods, entries, new CommissionCalculator(),
                salonConfig, aggregator, mock(RetentionAnalyticsService.class), manualAdjustments, expenses);

        when(salonConfig.findById(1)).thenReturn(Optional.of(CFG));
        // Default: no periods for any year (overridden per test)
        when(payPeriods.findAllByYearOrderByMonthAscHalfAsc(anyInt())).thenReturn(List.of());
        when(entries.findAllByPayPeriodId(anyLong())).thenReturn(List.of());
        // No manual adjustments by default — individual tests can override to exercise the fold-in.
        when(manualAdjustments.totalGrossForMonth(anyInt(), anyInt())).thenReturn(BigDecimal.ZERO);
        when(manualAdjustments.countedUnitDeltaForMonth(anyInt(), anyInt(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(0);
        // No expenses by default — individual tests can override to exercise net-revenue math.
        when(expenses.resolveExpenseTotal(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("Settled months aggregate card + cash revenue correctly from PeriodEntry")
    void settledMonthsAggregateRevenue() {
        Provider anna = provider(1L, "Anna");
        PayPeriod jan1 = period(1L, 2025, 1, Half.FIRST);
        PayPeriod jan2 = period(2L, 2025, 1, Half.SECOND);

        when(payPeriods.findAllByYearOrderByMonthAscHalfAsc(2025))
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
        when(payPeriods.findAllByYearOrderByMonthAscHalfAsc(2025)).thenReturn(List.of(jan1));
        // Card-only: gross = 1000, payroll = 1000 * 0.45 = 450
        when(entries.findAllByPayPeriodId(1L))
                .thenReturn(List.of(entry(anna, jan1, "1000.00", "0.00", "0.00", 10)));

        ExpenseService expenses = mock(ExpenseService.class);
        when(expenses.resolveExpenseTotal(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new BigDecimal("100.00"));
        OwnerOverviewService serviceWithExpenses = new OwnerOverviewService(payPeriods, entries,
                new CommissionCalculator(), salonConfig, aggregator, mock(RetentionAnalyticsService.class),
                mock(ManualAdjustmentService.class), expenses);

        MonthSummary jan = serviceWithExpenses.overview(2025, 1, 2025, 12).months().get(0);

        assertThat(jan.grossRevenue()).isEqualByComparingTo("1000.00");
        assertThat(jan.payrollCost()).isEqualByComparingTo("450.00");
        assertThat(jan.expenseTotal()).isEqualByComparingTo("100.00");
        // net = 1000 - 450 - 100 = 450
        assertThat(jan.netRevenue()).isEqualByComparingTo("450.00");
    }

    @Test
    @DisplayName("Payroll % is ~45% for a base-rate provider with no adjustments")
    void payrollPctIsApprox45ForBaseRate() {
        Provider anna = provider(1L, "Anna");
        PayPeriod mar1 = period(3L, 2025, 3, Half.FIRST);

        when(payPeriods.findAllByYearOrderByMonthAscHalfAsc(2025))
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
        when(payPeriods.findAllByYearOrderByMonthAscHalfAsc(futureYear)).thenReturn(List.of());

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

        when(payPeriods.findAllByYearOrderByMonthAscHalfAsc(2025)).thenReturn(List.of(mar1));
        when(entries.findAllByPayPeriodId(3L)).thenReturn(List.of(
                entry(anna, mar1, "800.00", "0.00", "0.00", 8),
                entry(kate, mar1, "1200.00", "0.00", "0.00", 12)));

        OwnerOverviewDto dto = service.overview(2025, 1, 2025, 12);
        assertThat(dto.providers()).hasSize(2);
        assertThat(dto.providers().get(0).name()).isEqualTo("Kate"); // higher gross first
        assertThat(dto.providers().get(1).name()).isEqualTo("Anna");
    }
}
