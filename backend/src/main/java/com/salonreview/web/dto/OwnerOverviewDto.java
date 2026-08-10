package com.salonreview.web.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record OwnerOverviewDto(
        int fromYear,
        int fromMonth,
        int toYear,
        int toMonth,
        List<MonthSummary> months,
        List<ProviderYtd> providers,
        YearTotals prevYear,
        /** When this response was actually computed (ISO instant) — see OwnerOverviewService's own
         * 30-day cache. Reflects the real last-Square-pull time for the requested range, not the
         * render time, same "honest synced badge" convention docs/CACHING.md already documents for
         * SquareClient/SettlementPreviewService. */
        String syncedAt
) {
    public record MonthSummary(
            int year,
            int month,
            String label,
            BigDecimal cardRevenue,
            BigDecimal cashRevenue,
            BigDecimal grossRevenue,
            BigDecimal tips,
            int procedures,
            BigDecimal avgPerAppt,
            BigDecimal payrollCost,
            BigDecimal payrollPct,
            boolean finalized,
            /** Distinct clients seen this month (from the visit ledger); 0 when unknown. */
            int clientsSeen,
            /** Of those, clients who had visited the salon before this month (returning); 0 when unknown. */
            int returningClients,
            /** Business expenses (materials/supplies, etc. — see ExpenseEntry) resolved for this
             * calendar month from the expense_entries ledger. Null alongside grossRevenue for a
             * month with no data at all (future/unknown), matching that field's own convention. */
            BigDecimal expenseTotal,
            /** Manager labor cost for this month: real clocked hours x rate when any clocked data
             * exists (see ManagerTimeService), otherwise the manual MANAGER_TIME expense-entry
             * backfill for months before that tracking existed. Null under the same conditions as
             * expenseTotal. */
            BigDecimal managerLaborCost,
            /** grossRevenue - payrollCost - expenseTotal - managerLaborCost — the bottom-line figure
             * this salon actually keeps, not just what came in the door. Null if any of the four is
             * null. */
            BigDecimal netRevenue,
            /** Whether a COMPLETED bank-statement reconciliation overlaps this month (see
             * ExpenseImportService.isPeriodStatementCovered) — when true, expenseTotal/
             * managerLaborCost above are real bank-linked figures (design.md D11); when false,
             * they're estimates (manual entries / clocked time). {@code payrollCost} and {@code
             * cashProviderCompensation} are sourced from SettlementPreviewService regardless of this
             * flag (see OwnerOverviewService.providerCompensationForMonth). Distinct from {@code
             * finalized}, which means "from settled PayPeriod rows" and has nothing to do with
             * statement reconciliation. */
            boolean statementCovered,
            /** The provider's share of cash revenue for this month — Σ(cashCollected -
             * monthCashToSalon) across providers, from SettlementPreviewService. This never becomes
             * a bank transaction (that's the nature of cash), so it's reported as its own P&L line
             * rather than folded into payrollCost. Null when unknown. */
            BigDecimal cashProviderCompensation,
            /** "Personal Bank Transactions" — categorized (not excluded) bank transactions in a
             * personal-flagged expense category. Reported separately; never subtracted from
             * netRevenue. Null when unknown. */
            BigDecimal personalBankTotal,
            /** "Owner Draws" — bank transactions excluded as OWNER_CONTRIBUTION or CASH_WITHDRAWAL.
             * Reported separately; never subtracted from netRevenue. Null when unknown. */
            BigDecimal ownerDrawsTotal,
            /** netRevenue - personalBankTotal - ownerDrawsTotal — a secondary "what's left after the
             * owner's own money movements" figure. netRevenue itself is never redefined by personal
             * withdrawals; this is purely additive. Null if any input is null. */
            BigDecimal profitAfterPersonal,
            /** "Other Cash Business Expenses" — manually-entered generic-category expenses flagged
             * paid-in-cash. Already subtracted into netRevenue; broken out here so the P&L can show
             * it as its own line. Null when unknown. */
            BigDecimal cashBusinessExpenseTotal,
            /** Category-by-category breakdown of expenseTotal + cashBusinessExpenseTotal combined —
             * keyed by expense category code, e.g. {"MATERIALS": 412.30, "SOFTWARE_SUBSCRIPTIONS":
             * 84.00}. Provider compensation and manager time aren't categories in this ledger, so
             * they never appear here — see their own dedicated fields. Null when unknown (same
             * conditions as expenseTotal); an empty map means genuinely zero categorized spend. */
            Map<String, BigDecimal> categoryBreakdown,
            /** Category-by-category breakdown of personalBankTotal — keyed by expense category code.
             * The owner can flag more than one category personal, so this can genuinely have more
             * than one row, e.g. {"PERSONAL": 1200.00, "OWNER_MEALS": 340.50}. Never subtracted from
             * netRevenue, same as personalBankTotal itself. Null when unknown (same conditions as
             * personalBankTotal); an empty map means genuinely zero personal spend. */
            Map<String, BigDecimal> personalBreakdown,
            /** The bank account's own printed opening/closing balance for this calendar month —
             * real cash movement through the account (best-effort extracted from the statement CSV
             * at upload time), not the accrual-based netRevenue figure. Deliberately NOT expected to
             * reconcile against Net: provider payroll is paid out whenever the Zelle transfer
             * actually lands, not necessarily within the calendar month the commission was earned.
             * Null when no completed statement overlapping this month captured a balance (older
             * imports, or an export format the parser couldn't extract balances from). */
            BigDecimal bankOpeningBalance,
            BigDecimal bankClosingBalance
    ) {
        /** Copy with the visit-ledger client counts filled in. */
        public MonthSummary withClients(int seen, int returning) {
            return new MonthSummary(year, month, label, cardRevenue, cashRevenue, grossRevenue, tips,
                    procedures, avgPerAppt, payrollCost, payrollPct, finalized, seen, returning,
                    expenseTotal, managerLaborCost, netRevenue, statementCovered, cashProviderCompensation,
                    personalBankTotal, ownerDrawsTotal, profitAfterPersonal, cashBusinessExpenseTotal,
                    categoryBreakdown, personalBreakdown, bankOpeningBalance, bankClosingBalance);
        }
    }

    public record ProviderYtd(
            Long providerId,
            String name,
            BigDecimal ytdGross,
            BigDecimal ytdPayroll,
            BigDecimal ytdPayrollPct
    ) {}

    public record YearTotals(
            BigDecimal totalGross,
            BigDecimal totalCard,
            BigDecimal totalCash
    ) {}
}
