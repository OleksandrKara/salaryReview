package com.salonreview.square;

import com.salonreview.commission.CommissionConfig;
import com.salonreview.commission.HalfInput;
import com.salonreview.commission.HalfSettlement;
import com.salonreview.commission.Stage;
import com.salonreview.domain.BankTransaction;
import com.salonreview.domain.Half;
import com.salonreview.domain.SalonConfig;
import com.salonreview.repo.BankTransactionRepository;
import com.salonreview.repo.PayPeriodRepository;
import com.salonreview.repo.PeriodEntryRepository;
import com.salonreview.repo.SalonConfigRepository;
import com.salonreview.service.CommissionCalculator;
import com.salonreview.web.dto.OwnerOverviewDto.MonthSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The P&L / Net Profit redesign's 8 required test scenarios, exercised against the live-month
 * ({@code fromSquare()}) path since that's what actually powers current/real months on
 * {@code /owner/overview/net} (the legacy {@code PeriodEntry}-backed {@code fromEntries()} path has
 * zero rows for any current month — see {@code OwnerOverviewServiceD11Test}, which covers scenarios
 * 5/6/8 against that path already). Provider compensation always comes from
 * {@link SettlementPreviewService} — never a second implementation — per the redesign's core design
 * decision (see {@code OwnerOverviewService.providerCompensationForMonth}). */
class OwnerOverviewServicePAndLTest {

    private static final SalonConfig CFG = SalonConfig.builder().id(1)
            .baseCommissionRate(new BigDecimal("0.45")).cardTipFeeRate(new BigDecimal("0.10")).build();

    private PayPeriodRepository payPeriods;
    private PeriodEntryRepository entries;
    private SalonConfigRepository salonConfig;
    private SquareMonthAggregator aggregator;
    private RetentionAnalyticsService retention;
    private ManualAdjustmentService manualAdjustments;
    private ExpenseService expenses;
    private ManagerTimeService managerTime;
    private ExpenseImportService expenseImports;
    private SettlementPreviewService settlementPreview;
    private BankTransactionRepository bankTransactions;
    private OwnerOverviewService service;

    @BeforeEach
    void setUp() {
        payPeriods = mock(PayPeriodRepository.class);
        entries = mock(PeriodEntryRepository.class);
        salonConfig = mock(SalonConfigRepository.class);
        com.salonreview.config.CurrentBusinessContext currentBusinessContext =
                mock(com.salonreview.config.CurrentBusinessContext.class);
        when(currentBusinessContext.id()).thenReturn(1L);
        // computeOverview()'s live-month path wraps fromSquare() in currentBusinessContext.runAs(...)
        // to carry the business id onto the async worker thread (see
        // OwnerOverviewServiceAsyncBusinessContextTest) — a plain mock's runAs() is a no-op that never
        // invokes the wrapped action, so every live month would resolve to null. Make it actually run.
        org.mockito.Mockito.doAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return null;
        }).when(currentBusinessContext).runAs(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
        when(salonConfig.findByBusinessId(1L)).thenReturn(Optional.of(CFG));
        when(payPeriods.findAllByBusinessIdAndYearOrderByMonthAscHalfAsc(eq(1L), anyInt())).thenReturn(List.of());

        aggregator = mock(SquareMonthAggregator.class);
        retention = mock(RetentionAnalyticsService.class);
        manualAdjustments = mock(ManualAdjustmentService.class);
        when(manualAdjustments.totalGrossForMonth(anyInt(), anyInt())).thenReturn(BigDecimal.ZERO);
        when(manualAdjustments.countedUnitDeltaForMonth(anyInt(), anyInt(), any())).thenReturn(0);

        expenses = mock(ExpenseService.class);
        when(expenses.resolveExpenseTotal(any(), any())).thenReturn(BigDecimal.ZERO);
        when(expenses.resolveCashBusinessExpenseTotal(any(), any())).thenReturn(BigDecimal.ZERO);
        when(expenses.resolvePersonalTotal(any(), any())).thenReturn(BigDecimal.ZERO);
        when(expenses.resolveExpenseBreakdownByCategory(any(), any())).thenReturn(Map.of());
        when(expenses.resolveCashBusinessExpenseBreakdownByCategory(any(), any())).thenReturn(Map.of());
        when(expenses.resolvePersonalBreakdownByCategory(any(), any())).thenReturn(Map.of());

        managerTime = mock(ManagerTimeService.class);
        when(managerTime.totalLaborCost(any(), any())).thenReturn(BigDecimal.ZERO);

        expenseImports = mock(ExpenseImportService.class);
        when(expenseImports.isPeriodStatementCovered(any(), any())).thenReturn(false);

        settlementPreview = mock(SettlementPreviewService.class);
        bankTransactions = mock(BankTransactionRepository.class);
        when(bankTransactions.sumOwnerDrawsForCompletedImportsOverlapping(anyList(), any(), any()))
                .thenReturn(BigDecimal.ZERO);

        service = new OwnerOverviewService(payPeriods, entries, new CommissionCalculator(), salonConfig,
                aggregator, retention, manualAdjustments, expenses, managerTime, expenseImports,
                settlementPreview, bankTransactions, currentBusinessContext);
    }

    /** One provider, one month, entirely card revenue — no cash at all. */
    private void stubSquareMonth(int year, int month, String cardRevenue, String cashGross, String cashCollected) {
        HalfInput half = new HalfInput(10, new BigDecimal(cardRevenue), BigDecimal.ZERO,
                new BigDecimal(cashGross), new BigDecimal(cashCollected), BigDecimal.ZERO);
        SquareMonthAggregator.ProviderMonth pm = new SquareMonthAggregator.ProviderMonth(
                "sq-1", "Anna", half, HalfInput.empty());
        SquareMonthAggregator.MonthAggregation agg = new SquareMonthAggregator.MonthAggregation(
                year, month, "America/Los_Angeles", List.of(pm), new SquareMonthAggregator.Diag(),
                List.of(), List.of(), List.of());
        when(aggregator.aggregate(year, month, CFG.getServicePriceCutoff())).thenReturn(agg);
    }

    /** A one-provider {@link SettlementPreviewService.SettlementPreview} whose derived card/cash
     * compensation figures are exactly {@code card}/{@code cash} — see
     * {@code OwnerOverviewServiceD11Test.preview} for the identical construction. */
    private static SettlementPreviewService.SettlementPreview preview(int year, int month,
                                                                       BigDecimal card, BigDecimal cash) {
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
        return new SettlementPreviewService.SettlementPreview(year, month, "America/Los_Angeles", config,
                BigDecimal.ZERO, List.of(payout), new SquareMonthAggregator.Diag(), year + "-" + month + "-01T00:00:00Z");
    }

    private MonthSummary monthFor(int year, int month) {
        return service.overview(year, month, year, month).months().get(0);
    }

    @Test
    @DisplayName("Scenario 1: card revenue + card provider expense")
    void cardRevenueAndCardProviderExpense() {
        stubSquareMonth(2026, 3, "1000.00", "0.00", "0.00");
        when(settlementPreview.preview(2026, 3)).thenReturn(preview(2026, 3, new BigDecimal("450.00"), BigDecimal.ZERO));

        MonthSummary mar = monthFor(2026, 3);

        assertThat(mar.grossRevenue()).isEqualByComparingTo("1000.00");
        assertThat(mar.payrollCost()).isEqualByComparingTo("450.00");
        assertThat(mar.cashProviderCompensation()).isEqualByComparingTo("0.00");
        // net = 1000 - 450 - 0 - 0 - 0 - 0 = 550
        assertThat(mar.netRevenue()).isEqualByComparingTo("550.00");
    }

    @Test
    @DisplayName("Scenario 2: cash revenue + cash provider share, with no bank transaction mocked at all")
    void cashRevenueAndCashProviderShareNoBankTransaction() {
        // 500 of cash revenue at menu price (cashGross), no discount.
        stubSquareMonth(2026, 3, "0.00", "500.00", "500.00");
        when(settlementPreview.preview(2026, 3)).thenReturn(preview(2026, 3, BigDecimal.ZERO, new BigDecimal("225.00")));

        MonthSummary mar = monthFor(2026, 3);

        assertThat(mar.cashRevenue()).isEqualByComparingTo("500.00");
        assertThat(mar.grossRevenue()).isEqualByComparingTo("500.00");
        // the provider's cash cut reduces Net even though no BankTransaction/ExpenseEntry exists
        // anywhere for it — sourced entirely from SettlementPreviewService.
        assertThat(mar.cashProviderCompensation()).isEqualByComparingTo("225.00");
        // net = 500 - 0(card payroll) - 225(cash comp) = 275 — the cash comp came entirely from
        // SettlementPreviewService, with no bank transaction or expense entry needed to represent it.
        assertThat(mar.netRevenue()).isEqualByComparingTo("275.00");
    }

    @Test
    @DisplayName("Scenario 3: a personal-categorized expense doesn't affect Net Profit")
    void personalExpenseDoesNotAffectNet() {
        stubSquareMonth(2026, 3, "1000.00", "0.00", "0.00");
        when(settlementPreview.preview(2026, 3)).thenReturn(preview(2026, 3, new BigDecimal("450.00"), BigDecimal.ZERO));
        when(expenses.resolvePersonalTotal(any(), any())).thenReturn(new BigDecimal("300.00"));

        MonthSummary mar = monthFor(2026, 3);

        assertThat(mar.personalBankTotal()).isEqualByComparingTo("300.00");
        // Net Profit is unaffected by personal spend — still 1000 - 450 = 550, not 250.
        assertThat(mar.netRevenue()).isEqualByComparingTo("550.00");
        assertThat(mar.profitAfterPersonal()).isEqualByComparingTo("250.00");
    }

    @Test
    @DisplayName("Scenario 4: an owner draw doesn't affect Net Profit")
    void ownerDrawDoesNotAffectNet() {
        stubSquareMonth(2026, 3, "1000.00", "0.00", "0.00");
        when(settlementPreview.preview(2026, 3)).thenReturn(preview(2026, 3, new BigDecimal("450.00"), BigDecimal.ZERO));
        when(bankTransactions.sumOwnerDrawsForCompletedImportsOverlapping(
                List.of(BankTransaction.EXCLUDE_OWNER_CONTRIBUTION, BankTransaction.EXCLUDE_CASH_WITHDRAWAL),
                java.time.LocalDate.of(2026, 3, 1), java.time.LocalDate.of(2026, 3, 31)))
                .thenReturn(new BigDecimal("-200.00")); // signed: money out

        MonthSummary mar = monthFor(2026, 3);

        assertThat(mar.ownerDrawsTotal()).isEqualByComparingTo("200.00");
        // Net Profit is unaffected by the owner's own withdrawal — still 550, not 350.
        assertThat(mar.netRevenue()).isEqualByComparingTo("550.00");
        assertThat(mar.profitAfterPersonal()).isEqualByComparingTo("350.00");
    }

    @Test
    @DisplayName("Scenario 5/6: each month's provider compensation is keyed strictly to its own "
            + "(year, month) — a settlement landing in the following month never leaks into it, and "
            + "vice versa")
    void eachMonthSourcesItsOwnProviderCompensationIndependently() {
        // July: services July 16-31, comp settled in August in reality — but preview(2026, 7) is
        // the ONLY thing OwnerOverviewService ever calls for July, so July's P&L reflects exactly
        // July's own service-period compensation regardless of when the Zelle/cash actually moves.
        stubSquareMonth(2026, 7, "2000.00", "0.00", "0.00");
        when(settlementPreview.preview(2026, 7)).thenReturn(preview(2026, 7, new BigDecimal("900.00"), BigDecimal.ZERO));
        // August: a different service-period figure entirely — proves no cross-month leakage.
        stubSquareMonth(2026, 8, "500.00", "0.00", "0.00");
        when(settlementPreview.preview(2026, 8)).thenReturn(preview(2026, 8, new BigDecimal("225.00"), BigDecimal.ZERO));

        MonthSummary jul = monthFor(2026, 7);
        MonthSummary aug = monthFor(2026, 8);

        assertThat(jul.payrollCost()).isEqualByComparingTo("900.00");
        assertThat(jul.netRevenue()).isEqualByComparingTo("1100.00"); // 2000 - 900
        assertThat(aug.payrollCost()).isEqualByComparingTo("225.00");
        assertThat(aug.netRevenue()).isEqualByComparingTo("275.00"); // 500 - 225
        verify(settlementPreview).preview(2026, 7);
        verify(settlementPreview).preview(2026, 8);
    }

    @Test
    @DisplayName("Scenario 7: a provider's cash settlement (their share of cash collected) is a "
            + "P&L-level adjustment, never double-counted as extra revenue")
    void cashSettlementIsNotDoubleCountedAsRevenue() {
        // 800 of cash revenue at menu price; the provider actually collected only 750 (a $50
        // discount the salon absorbs) and hands back monthCashToSalon = 500 of it.
        stubSquareMonth(2026, 3, "0.00", "800.00", "750.00");
        // cashCollected(750) - monthCashToSalon(500, via the ProviderPayout's own monthCashToSalon
        // field, not exposed through this preview() fixture's simplified card/cash split) = 250.
        HalfSettlement half = new HalfSettlement(Half.FIRST, Stage.PROVISIONAL_FIRST_HALF, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("750.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        HalfSettlement empty = new HalfSettlement(Half.SECOND, Stage.FINAL_MONTH_CLOSE, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        SettlementPreviewService.ProviderPayout payout = new SettlementPreviewService.ProviderPayout(
                1L, "Anna", 0, false, false, false, half, empty, BigDecimal.ZERO, new BigDecimal("500.00"),
                null, null, null, null, 0, 0, 0, 0, 0, 0);
        CommissionConfig config = new CommissionConfig(60, new BigDecimal("0.45"), new BigDecimal("0.50"),
                new BigDecimal("0.10"));
        when(settlementPreview.preview(2026, 3)).thenReturn(new SettlementPreviewService.SettlementPreview(
                2026, 3, "America/Los_Angeles", config, BigDecimal.ZERO, List.of(payout),
                new SquareMonthAggregator.Diag(), "2026-03-01T00:00:00Z"));

        MonthSummary mar = monthFor(2026, 3);

        // Gross Revenue counts the full 800 of cash revenue exactly once, regardless of the
        // provider's settlement split.
        assertThat(mar.cashRevenue()).isEqualByComparingTo("800.00");
        // The provider's cash comp (what the salon nets in its own pocket vs. what it hands the
        // provider) is exactly cashCollected - monthCashToSalon = 750 - 500 = 250 — not 800, not
        // 750 + 500, proving the settlement isn't double-counted as extra revenue.
        assertThat(mar.cashProviderCompensation()).isEqualByComparingTo("250.00");
        // net = 800 - 0(card) - 250(cash comp) = 550
        assertThat(mar.netRevenue()).isEqualByComparingTo("550.00");
    }

    @Test
    @DisplayName("categoryBreakdown merges the manual bank breakdown with the cash breakdown when the month isn't statement-covered")
    void categoryBreakdownMergesManualAndCash() {
        stubSquareMonth(2026, 3, "1000.00", "0.00", "0.00");
        when(settlementPreview.preview(2026, 3)).thenReturn(preview(2026, 3, new BigDecimal("450.00"), BigDecimal.ZERO));
        LocalDate from = LocalDate.of(2026, 3, 1), to = LocalDate.of(2026, 3, 31);
        when(expenseImports.isPeriodStatementCovered(from, to)).thenReturn(false);
        when(expenses.resolveExpenseBreakdownByCategory(from, to))
                .thenReturn(Map.of("MATERIALS", new BigDecimal("120.00")));
        when(expenses.resolveCashBusinessExpenseBreakdownByCategory(from, to))
                .thenReturn(Map.of("CONTRACTORS", new BigDecimal("40.00")));

        MonthSummary mar = monthFor(2026, 3);

        assertThat(mar.categoryBreakdown()).containsOnly(
                entry("MATERIALS", new BigDecimal("120.00")), entry("CONTRACTORS", new BigDecimal("40.00")));
    }

    @Test
    @DisplayName("categoryBreakdown sources the bank side from the statement-derived breakdown when reconciled, summing with the same-category cash side rather than overwriting it")
    void categoryBreakdownUsesStatementDerivedWhenCovered() {
        stubSquareMonth(2026, 3, "1000.00", "0.00", "0.00");
        when(settlementPreview.preview(2026, 3)).thenReturn(preview(2026, 3, new BigDecimal("450.00"), BigDecimal.ZERO));
        LocalDate from = LocalDate.of(2026, 3, 1), to = LocalDate.of(2026, 3, 31);
        when(expenseImports.isPeriodStatementCovered(from, to)).thenReturn(true);
        when(expenseImports.linkedExpenseEntryIds(from, to)).thenReturn(List.of(1L, 2L));
        when(expenses.resolveStatementDerivedExpenseBreakdownByCategory(List.of(1L, 2L)))
                .thenReturn(Map.of("MATERIALS", new BigDecimal("300.00")));
        when(expenses.resolveCashBusinessExpenseBreakdownByCategory(from, to))
                .thenReturn(Map.of("MATERIALS", new BigDecimal("10.00")));

        MonthSummary mar = monthFor(2026, 3);

        assertThat(mar.categoryBreakdown()).containsOnly(entry("MATERIALS", new BigDecimal("310.00")));
        verify(expenses, never()).resolveExpenseBreakdownByCategory(any(), any());
    }

    @Test
    @DisplayName("personalBreakdown sources from the manual resolver when the month isn't statement-covered")
    void personalBreakdownUsesManualWhenNotCovered() {
        stubSquareMonth(2026, 3, "1000.00", "0.00", "0.00");
        when(settlementPreview.preview(2026, 3)).thenReturn(preview(2026, 3, new BigDecimal("450.00"), BigDecimal.ZERO));
        LocalDate from = LocalDate.of(2026, 3, 1), to = LocalDate.of(2026, 3, 31);
        when(expenseImports.isPeriodStatementCovered(from, to)).thenReturn(false);
        when(expenses.resolvePersonalBreakdownByCategory(from, to))
                .thenReturn(Map.of("PERSONAL", new BigDecimal("300.00")));

        MonthSummary mar = monthFor(2026, 3);

        assertThat(mar.personalBreakdown()).containsOnly(entry("PERSONAL", new BigDecimal("300.00")));
        verify(expenses, never()).resolveStatementDerivedPersonalBreakdownByCategory(any());
    }

    @Test
    @DisplayName("personalBreakdown sources from the statement-derived resolver when reconciled, can span multiple personal categories")
    void personalBreakdownUsesStatementDerivedWhenCovered() {
        stubSquareMonth(2026, 3, "1000.00", "0.00", "0.00");
        when(settlementPreview.preview(2026, 3)).thenReturn(preview(2026, 3, new BigDecimal("450.00"), BigDecimal.ZERO));
        LocalDate from = LocalDate.of(2026, 3, 1), to = LocalDate.of(2026, 3, 31);
        when(expenseImports.isPeriodStatementCovered(from, to)).thenReturn(true);
        when(expenseImports.linkedExpenseEntryIds(from, to)).thenReturn(List.of(1L, 2L));
        when(expenses.resolveStatementDerivedPersonalBreakdownByCategory(List.of(1L, 2L)))
                .thenReturn(Map.of("PERSONAL", new BigDecimal("180.00"), "OWNER_MEALS", new BigDecimal("65.00")));

        MonthSummary mar = monthFor(2026, 3);

        assertThat(mar.personalBreakdown()).containsOnly(
                entry("PERSONAL", new BigDecimal("180.00")), entry("OWNER_MEALS", new BigDecimal("65.00")));
        verify(expenses, never()).resolvePersonalBreakdownByCategory(any(), any());
    }

    @Test
    @DisplayName("bankOpeningBalance/bankClosingBalance are threaded through from ExpenseImportService.bankBalanceForMonth")
    void bankBalanceIsThreadedThrough() {
        stubSquareMonth(2026, 3, "1000.00", "0.00", "0.00");
        when(settlementPreview.preview(2026, 3)).thenReturn(preview(2026, 3, new BigDecimal("450.00"), BigDecimal.ZERO));
        LocalDate from = LocalDate.of(2026, 3, 1), to = LocalDate.of(2026, 3, 31);
        when(expenseImports.bankBalanceForMonth(from, to))
                .thenReturn(new ExpenseImportService.BankBalance(new BigDecimal("9192.33"), new BigDecimal("8550.84")));

        MonthSummary mar = monthFor(2026, 3);

        assertThat(mar.bankOpeningBalance()).isEqualByComparingTo("9192.33");
        assertThat(mar.bankClosingBalance()).isEqualByComparingTo("8550.84");
    }

    @Test
    @DisplayName("bankOpeningBalance/bankClosingBalance are null when ExpenseImportService has no balance for the month")
    void bankBalanceIsNullWhenUnavailable() {
        stubSquareMonth(2026, 3, "1000.00", "0.00", "0.00");
        when(settlementPreview.preview(2026, 3)).thenReturn(preview(2026, 3, new BigDecimal("450.00"), BigDecimal.ZERO));
        LocalDate from = LocalDate.of(2026, 3, 1), to = LocalDate.of(2026, 3, 31);
        when(expenseImports.bankBalanceForMonth(from, to)).thenReturn(null);

        MonthSummary mar = monthFor(2026, 3);

        assertThat(mar.bankOpeningBalance()).isNull();
        assertThat(mar.bankClosingBalance()).isNull();
    }
}
