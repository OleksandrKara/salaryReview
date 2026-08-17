package com.salonreview.square;

import com.salonreview.domain.BankTransaction;
import com.salonreview.domain.PayPeriod;
import com.salonreview.domain.PeriodEntry;
import com.salonreview.domain.SalonConfig;
import com.salonreview.repo.BankTransactionRepository;
import com.salonreview.repo.PayPeriodRepository;
import com.salonreview.repo.PeriodEntryRepository;
import com.salonreview.repo.SalonConfigRepository;
import com.salonreview.service.CommissionCalculator;
import com.salonreview.service.SettlementLine;
import com.salonreview.web.dto.OwnerOverviewDto;
import com.salonreview.web.dto.OwnerOverviewDto.MonthSummary;
import com.salonreview.web.dto.RetentionSeries;
import com.salonreview.web.dto.OwnerOverviewDto.ProviderYtd;
import com.salonreview.web.dto.OwnerOverviewDto.YearTotals;
import com.salonreview.util.TtlCache;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class OwnerOverviewService {

    /** Hard cap: refuse ranges longer than 24 months to bound Square API calls. */
    private static final int MAX_MONTHS = 24;

    /** This dashboard is reviewed occasionally (weekly/monthly), not watched live, and each
     * assembled response already layers a real Square pull (via SquareMonthAggregator) plus
     * several DB aggregations on top of SquareClient's own much-shorter 10-minute cache — see
     * docs/CACHING.md. 30 days keeps a normal visit to this page instant indefinitely between
     * visits; the "Sync now" button (wired through SquareSyncController, same one used elsewhere
     * in the app) busts it on demand, and the honest {@code syncedAt} badge on the page makes the
     * staleness visible rather than silent. Restarting/redeploying the backend also clears it
     * (in-memory, per-instance — same operational note as every other cache in this app). */
    private static final Duration CACHE_TTL = Duration.ofDays(30);
    private final TtlCache cache = new TtlCache();
    /** When the most recent response (for any requested range) was actually computed — mirrors
     * SquareClient's own single shared lastFetchAt field (see docs/CACHING.md): not per-range,
     * just "the last time this service actually did real work" for an honest badge. */
    private volatile Instant lastFetchAt = Instant.now();

    /** Owner-draw exclude reasons — see {@code BankTransactionRepository.sumOwnerDrawsForCompletedImportsOverlapping}. */
    private static final List<String> OWNER_DRAW_REASONS =
            List.of(BankTransaction.EXCLUDE_OWNER_CONTRIBUTION, BankTransaction.EXCLUDE_CASH_WITHDRAWAL);

    private final PayPeriodRepository payPeriods;
    private final PeriodEntryRepository entries;
    private final CommissionCalculator calculator;
    private final SalonConfigRepository salonConfig;
    private final SquareMonthAggregator aggregator;
    private final RetentionAnalyticsService retention;
    private final ManualAdjustmentService manualAdjustments;
    private final ExpenseService expenses;
    private final ManagerTimeService managerTime;
    private final ExpenseImportService expenseImports;
    private final SettlementPreviewService settlementPreview;
    private final BankTransactionRepository bankTransactions;
    private final com.salonreview.config.CurrentBusinessContext currentBusinessContext;

    public OwnerOverviewService(PayPeriodRepository payPeriods, PeriodEntryRepository entries,
                                CommissionCalculator calculator, SalonConfigRepository salonConfig,
                                SquareMonthAggregator aggregator, RetentionAnalyticsService retention,
                                ManualAdjustmentService manualAdjustments, ExpenseService expenses,
                                ManagerTimeService managerTime, ExpenseImportService expenseImports,
                                SettlementPreviewService settlementPreview, BankTransactionRepository bankTransactions,
                                com.salonreview.config.CurrentBusinessContext currentBusinessContext) {
        this.expenseImports = expenseImports;
        this.payPeriods = payPeriods;
        this.entries = entries;
        this.calculator = calculator;
        this.salonConfig = salonConfig;
        this.currentBusinessContext = currentBusinessContext;
        this.aggregator = aggregator;
        this.retention = retention;
        this.manualAdjustments = manualAdjustments;
        this.expenses = expenses;
        this.managerTime = managerTime;
        this.settlementPreview = settlementPreview;
        this.bankTransactions = bankTransactions;
    }

    public OwnerOverviewDto overview(int fromYear, int fromMonth, int toYear, int toMonth) {
        // businessId in the key, not just an argument to computeOverview() — this cache is a
        // 30-day TTL (see CACHE_TTL's own doc), so without it a second business's first read of
        // any range business A already computed would silently return business A's P&L instead
        // of running its own computeOverview(), for up to 30 days.
        String key = currentBusinessContext.id() + ":" + fromYear + "-" + fromMonth + ":" + toYear + "-" + toMonth;
        return cache.get(key, CACHE_TTL, () -> computeOverview(fromYear, fromMonth, toYear, toMonth));
    }

    /** Drops only this business's own cached ranges — backs the "Sync now" button (see
     * SquareSyncController) so an owner can force a fresh Square pull without waiting out the
     * 30-day TTL, without also forcing every other business's already-fresh cache to recompute. */
    public void invalidateCache() {
        cache.invalidateWhere(k -> k.startsWith(currentBusinessContext.id() + ":"));
    }

    private OwnerOverviewDto computeOverview(int fromYear, int fromMonth, int toYear, int toMonth) {
        Long businessId = currentBusinessContext.id();
        SalonConfig cfg = salonConfig.findByBusinessId(businessId)
                .orElseThrow(() -> new IllegalStateException("Salon config for business " + businessId + " is missing"));

        LocalDate today = LocalDate.now();
        int currentYear = today.getYear();
        int currentMonth = today.getMonthValue();

        // Build the ordered list of (year, month) tuples in the requested range, capped at 24.
        List<int[]> range = buildRange(fromYear, fromMonth, toYear, toMonth);

        // Collect settled PayPeriod entries for all years that appear in the range.
        Set<Integer> yearsNeeded = range.stream().map(ym -> ym[0]).collect(Collectors.toSet());
        // key = "YYYY-M"
        Map<String, List<PeriodEntry>> entriesByYearMonth = new HashMap<>();
        for (int yr : yearsNeeded) {
            for (PayPeriod pp : payPeriods.findAllByBusinessIdAndYearOrderByMonthAscHalfAsc(businessId, yr)) {
                List<PeriodEntry> monthEntries = entries.findAllByPayPeriodId(pp.getId());
                if (!monthEntries.isEmpty()) {
                    entriesByYearMonth
                            .computeIfAbsent(ymKey(pp.getYear(), pp.getMonth()), k -> new ArrayList<>())
                            .addAll(monthEntries);
                }
            }
        }

        // Determine which months need a live Square fetch (past/current, not yet settled).
        List<int[]> liveNeeded = range.stream()
                .filter(ym -> !entriesByYearMonth.containsKey(ymKey(ym[0], ym[1])))
                .filter(ym -> !isFuture(ym[0], ym[1], currentYear, currentMonth))
                .toList();

        Map<String, MonthSummary> liveResults = new ConcurrentHashMap<>();
        if (!liveNeeded.isEmpty()) {
            // fromSquare() reaches settlementPreview.preview() -> CurrentBusinessContext.id(), but
            // CompletableFuture.runAsync hands each task to a different pool thread — a ThreadLocal
            // set on THIS (calling) thread does not carry over. Resolve it here, once, on the calling
            // thread, and re-establish it explicitly inside each async task via runAs; without this
            // every live-month fetch below throws on the worker thread, which
            // providerCompensationForMonth's own catch(RuntimeException) then silently swallows into
            // a zeroed-out cash figure — confirmed by a real regression-snapshot diff before this fix
            // shipped, not a hypothetical. (businessId already resolved above, before this method's
            // own salon_config lookup — reused here rather than re-read, same value either way.)
            CompletableFuture<?>[] futures = liveNeeded.stream()
                    .map(ym -> CompletableFuture.runAsync(() -> currentBusinessContext.runAs(businessId,
                            () -> liveResults.put(ymKey(ym[0], ym[1]), fromSquare(ym[1], ym[0], cfg)))))
                    .toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(futures).join();
        }

        // Salon-level distinct/returning client counts per month, from the visit ledger (same source as
        // the Retention page). Best-effort — the ledger backfills a rolling window, so months it doesn't
        // yet cover simply report 0 and the UI treats them as unknown.
        Map<String, int[]> clientCounts = clientCountsByMonth(fromYear, fromMonth, toYear, toMonth);

        // Assemble summaries and accumulate provider totals (settled months only).
        Map<Long, ProviderAcc> providerAccs = new LinkedHashMap<>();
        List<MonthSummary> months = new ArrayList<>(range.size());
        for (int[] ym : range) {
            String key = ymKey(ym[0], ym[1]);
            MonthSummary base;
            if (entriesByYearMonth.containsKey(key)) {
                base = fromEntries(ym[0], ym[1], entriesByYearMonth.get(key), cfg, providerAccs);
            } else if (liveResults.containsKey(key)) {
                base = liveResults.get(key);
            } else {
                base = emptyMonth(ym[0], ym[1]);
            }
            int[] c = clientCounts.get(key);
            months.add(c == null ? base : base.withClients(c[0], c[1]));
        }

        List<ProviderYtd> providers = providerAccs.values().stream()
                .map(a -> new ProviderYtd(a.providerId, a.name, a.gross,
                        a.payroll, pct(a.payroll, a.gross)))
                .sorted(Comparator.comparing(ProviderYtd::ytdGross).reversed())
                .toList();

        // Prior-period totals: same range shifted back one year (DB only, best-effort).
        YearTotals prevYear = prevPeriodTotals(fromYear - 1, fromMonth, toYear - 1, toMonth);

        lastFetchAt = Instant.now();
        return new OwnerOverviewDto(fromYear, fromMonth, toYear, toMonth, months, providers, prevYear,
                lastFetchAt.toString());
    }

    // --- settled month from DB ---

    private MonthSummary fromEntries(int year, int month, List<PeriodEntry> monthEntries,
                                     SalonConfig cfg, Map<Long, ProviderAcc> providerAccs) {
        BigDecimal card = BigDecimal.ZERO, cash = BigDecimal.ZERO,
                   tips = BigDecimal.ZERO, payroll = BigDecimal.ZERO;
        int procedures = 0;

        for (PeriodEntry e : monthEntries) {
            card       = card.add(e.getCardTotal());
            cash       = cash.add(e.getCashTotal());
            tips       = tips.add(e.getCardTips());
            procedures += e.getProcedures();

            SettlementLine line = calculator.calculate(e.getProvider(), e);
            BigDecimal providerPayroll = line.zelleToProvider()
                    .add(e.getCashTotal().subtract(line.cashToSalon()));
            payroll = payroll.add(providerPayroll);

            Long pid = e.getProvider().getId();
            ProviderAcc acc = providerAccs.computeIfAbsent(pid,
                    k -> new ProviderAcc(pid, e.getProvider().getDisplayName()));
            acc.gross   = acc.gross.add(e.getCardTotal()).add(e.getCashTotal());
            acc.payroll = acc.payroll.add(providerPayroll);
        }

        BigDecimal gross = card.add(cash);
        ProviderCompensation comp = providerCompensationForMonth(year, month);
        payroll = comp.card() != null ? comp.card() : payroll;
        // When SettlementPreviewService has no data for the month (e.g. Square lookup failed), the
        // fallback `payroll` above is a *combined* card+cash commission estimate (see the
        // zelleToProvider + (cashTotal - cashToSalon) loop above / the flat-rate formula in
        // fromSquare) — so cashProviderCompensation must default to zero, not propagate null, or
        // the same cash commission would either double-count (if summed) or null out netRevenue.
        BigDecimal cashProviderCompensation = comp.cash() != null
                ? comp.cash() : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal expenseTotal = expenseTotalForMonth(year, month);
        BigDecimal managerLaborCost = managerLaborCostForMonth(year, month);
        BigDecimal cashBusinessExpenseTotal = cashBusinessExpenseTotalForMonth(year, month);
        BigDecimal personalBankTotal = personalBankTotalForMonth(year, month);
        BigDecimal ownerDrawsTotal = ownerDrawsTotalForMonth(year, month);
        BigDecimal netProfit = netRevenue(gross, payroll, cashProviderCompensation, expenseTotal,
                cashBusinessExpenseTotal, managerLaborCost);
        ExpenseImportService.BankBalance bankBalance = bankBalanceForMonth(year, month);
        return new MonthSummary(year, month, label(month), card, cash, gross, tips, procedures,
                avg(gross, procedures), payroll, pct(payroll, gross), true, 0, 0,
                expenseTotal, managerLaborCost, netProfit,
                statementCoveredForMonth(year, month), cashProviderCompensation, personalBankTotal,
                ownerDrawsTotal, profitAfterPersonal(netProfit, personalBankTotal, ownerDrawsTotal),
                cashBusinessExpenseTotal, expenseCategoryBreakdownForMonth(year, month),
                personalBreakdownForMonth(year, month),
                bankBalance == null ? null : bankBalance.opening(), bankBalance == null ? null : bankBalance.closing());
    }

    // --- live month from Square ---

    private MonthSummary fromSquare(int month, int year, SalonConfig cfg) {
        try {
            SquareMonthAggregator.MonthAggregation agg =
                    aggregator.aggregate(year, month, cfg.getServicePriceCutoff());

            BigDecimal card = BigDecimal.ZERO, cash = BigDecimal.ZERO, tips = BigDecimal.ZERO;
            int procedures = 0;
            for (SquareMonthAggregator.ProviderMonth pm : agg.providers()) {
                card       = card.add(pm.firstHalf().cardRevenue()).add(pm.secondHalf().cardRevenue());
                cash       = cash.add(pm.firstHalf().cashGross()).add(pm.secondHalf().cashGross());
                tips       = tips.add(pm.firstHalf().cardTips()).add(pm.secondHalf().cardTips());
                procedures += pm.firstHalf().countedServices() + pm.secondHalf().countedServices();
            }
            // Manual adjustments (credits or deductions like a refund) aren't Square orders, so the
            // aggregator above never sees them — fold them in here too, the same way
            // SettlementPreviewService does for payroll, so this "live" revenue figure isn't
            // silently stale relative to what providers actually get paid on.
            card       = card.add(manualAdjustments.totalGrossForMonth(year, month));
            procedures += manualAdjustments.countedUnitDeltaForMonth(year, month, cfg.getServicePriceCutoff());

            BigDecimal gross   = card.add(cash);
            BigDecimal rate    = cfg.getBaseCommissionRate();
            BigDecimal feeRate = cfg.getCardTipFeeRate();
            BigDecimal payroll = gross.multiply(rate)
                    .add(tips.multiply(BigDecimal.ONE.subtract(feeRate)))
                    .setScale(2, RoundingMode.HALF_UP);
            ProviderCompensation comp = providerCompensationForMonth(year, month);
            payroll = comp.card() != null ? comp.card() : payroll;
            // See fromEntries()'s identical fallback: the flat-rate estimate above already combines
            // card+cash commission, so a missing Square figure must default cash comp to zero.
            BigDecimal cashProviderCompensation = comp.cash() != null
                    ? comp.cash() : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

            BigDecimal expenseTotal = expenseTotalForMonth(year, month);
            BigDecimal managerLaborCost = managerLaborCostForMonth(year, month);
            BigDecimal cashBusinessExpenseTotal = cashBusinessExpenseTotalForMonth(year, month);
            BigDecimal personalBankTotal = personalBankTotalForMonth(year, month);
            BigDecimal ownerDrawsTotal = ownerDrawsTotalForMonth(year, month);
            BigDecimal netProfit = netRevenue(gross, payroll, cashProviderCompensation, expenseTotal,
                    cashBusinessExpenseTotal, managerLaborCost);
            ExpenseImportService.BankBalance bankBalance = bankBalanceForMonth(year, month);
            return new MonthSummary(year, month, label(month), card, cash, gross, tips, procedures,
                    avg(gross, procedures), payroll, pct(payroll, gross), false, 0, 0,
                    expenseTotal, managerLaborCost, netProfit,
                    statementCoveredForMonth(year, month), cashProviderCompensation, personalBankTotal,
                    ownerDrawsTotal, profitAfterPersonal(netProfit, personalBankTotal, ownerDrawsTotal),
                    cashBusinessExpenseTotal, expenseCategoryBreakdownForMonth(year, month),
                    personalBreakdownForMonth(year, month),
                    bankBalance == null ? null : bankBalance.opening(), bankBalance == null ? null : bankBalance.closing());
        } catch (RuntimeException e) {
            return emptyMonth(year, month);
        }
    }

    /** Resolves this calendar month's business expenses (see ExpenseService/ExpenseResolver) —
     * best-effort, same resilience convention as clientCountsByMonth below: a lookup failure here
     * must not take down the whole Overview dashboard, it just leaves that month's net-revenue
     * figure unknown. For a month with a completed statement reconciliation overlapping it, this
     * sources exclusively from that reconciliation's own linked entries instead (openspec design.md
     * D11) — manual entries for an already-covered month don't get folded in on top. */
    private BigDecimal expenseTotalForMonth(int year, int month) {
        try {
            YearMonth ym = YearMonth.of(year, month);
            LocalDate from = ym.atDay(1), to = ym.atEndOfMonth();
            if (expenseImports.isPeriodStatementCovered(from, to)) {
                return expenses.resolveStatementDerivedExpenseTotal(expenseImports.linkedExpenseEntryIds(from, to));
            }
            return expenses.resolveExpenseTotal(from, to);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Resolves this calendar month's manager labor cost: the real clocked total (see
     * {@code ManagerTimeService.totalLaborCost}) whenever any clocked data exists for the month,
     * falling back to the manual MANAGER_TIME expense-entry backfill for months before manager
     * time tracking existed (see {@code ExpenseService.resolveManagerLaborManualTotal}). For a
     * month with a completed statement reconciliation, neither of those applies — the
     * reconciliation's own linked MANAGER_TIME entries are the exclusive source instead (openspec
     * design.md D11), so the same real disbursement is never subtracted twice. Same best-effort
     * resilience convention as expenseTotalForMonth. */
    private BigDecimal managerLaborCostForMonth(int year, int month) {
        try {
            YearMonth ym = YearMonth.of(year, month);
            LocalDate from = ym.atDay(1), to = ym.atEndOfMonth();
            if (expenseImports.isPeriodStatementCovered(from, to)) {
                return expenses.resolveStatementDerivedManagerLaborTotal(expenseImports.linkedExpenseEntryIds(from, to));
            }
            BigDecimal auto = managerTime.totalLaborCost(from, to);
            if (auto != null) return auto;
            return expenses.resolveManagerLaborManualTotal(from, to);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Provider compensation for a calendar month, card and cash, sourced exclusively from {@link
     * SettlementPreviewService} — the same engine that drives the Salary/Commission Report
     * (/reports, /me), so there is exactly one source of truth for provider pay and no risk of a
     * second, divergent computation. {@code preview(year, month)} attributes every dollar to the
     * calendar month the underlying service happened (via Square booking/order dates), never to
     * whenever the Zelle transfer or cash handoff actually settles — this is what prevents e.g.
     * July 16-31 provider comp (typically settled in August) from ever being counted in August's
     * P&L too: August's own {@code preview(2026, 8)} call only ever sees August's services.
     * Bank-categorized {@code PROVIDER_PAYROLL} transactions are deliberately NOT used here (see
     * {@code ExpenseService.resolveStatementDerivedProviderPayrollTotal}'s doc comment) — using both
     * would double-count the same disbursement and reintroduce the same settlement-timing bug this
     * method exists to close. Best-effort: a Square-side failure returns null for both figures
     * rather than taking down the whole dashboard; callers fall back to the formula-computed
     * card figure they already have (cash has no such fallback — it was never computed anywhere
     * else). */
    private ProviderCompensation providerCompensationForMonth(int year, int month) {
        try {
            SettlementPreviewService.SettlementPreview preview = settlementPreview.preview(year, month);
            BigDecimal card = BigDecimal.ZERO, cash = BigDecimal.ZERO;
            for (SettlementPreviewService.ProviderPayout p : preview.providers()) {
                BigDecimal cashCollected = p.firstHalf().cashCollected().add(p.secondHalf().cashCollected());
                card = card.add(p.monthZelleToProvider());
                cash = cash.add(cashCollected.subtract(p.monthCashToSalon()));
            }
            return new ProviderCompensation(card.setScale(2, RoundingMode.HALF_UP), cash.setScale(2, RoundingMode.HALF_UP));
        } catch (RuntimeException e) {
            return new ProviderCompensation(null, null);
        }
    }

    private record ProviderCompensation(BigDecimal card, BigDecimal cash) {}

    /** "Personal Bank Transactions" for a calendar month — categorized (not excluded) transactions
     * in a personal-flagged category (see {@code ExpenseCategoryDefinition.personal}). Reported
     * separately; never subtracted from Net Profit. Same statement-covered-vs-manual split as
     * {@link #expenseTotalForMonth}. */
    private BigDecimal personalBankTotalForMonth(int year, int month) {
        try {
            YearMonth ym = YearMonth.of(year, month);
            LocalDate from = ym.atDay(1), to = ym.atEndOfMonth();
            if (expenseImports.isPeriodStatementCovered(from, to)) {
                return expenses.resolveStatementDerivedPersonalTotal(expenseImports.linkedExpenseEntryIds(from, to));
            }
            return expenses.resolvePersonalTotal(from, to);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** "Owner Draws" for a calendar month — bank transactions excluded as OWNER_CONTRIBUTION or
     * CASH_WITHDRAWAL for any COMPLETED import overlapping the month. These never produce an
     * {@code expense_entries} row at all, so there's no statement-covered/manual split here, just a
     * direct sum. Reported separately; never subtracted from Net Profit. */
    private BigDecimal ownerDrawsTotalForMonth(int year, int month) {
        try {
            YearMonth ym = YearMonth.of(year, month);
            LocalDate from = ym.atDay(1), to = ym.atEndOfMonth();
            BigDecimal sum = bankTransactions.sumOwnerDrawsForCompletedImportsOverlapping(OWNER_DRAW_REASONS, from, to);
            return sum == null ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP) : sum.abs().setScale(2, RoundingMode.HALF_UP);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** "Other Cash Business Expenses" for a calendar month — manually-entered generic-category
     * expenses flagged paid-in-cash (see {@code ExpenseService.resolveCashBusinessExpenseTotal}).
     * No statement-covered gating: these are cash-paid by definition, so they can never be part of
     * a bank reconciliation's linked entries in the first place. */
    private BigDecimal cashBusinessExpenseTotalForMonth(int year, int month) {
        try {
            YearMonth ym = YearMonth.of(year, month);
            return expenses.resolveCashBusinessExpenseTotal(ym.atDay(1), ym.atEndOfMonth());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Category breakdown of "Business Expenses" (Bank + Other Cash combined) for a calendar
     * month — same statement-covered/manual split as {@link #expenseTotalForMonth} for the bank
     * side, and the same always-manual cash side as {@link #cashBusinessExpenseTotalForMonth}, so
     * the two together always sum to exactly {@code expenseTotal + cashBusinessExpenseTotal}.
     * Provider compensation and manager time aren't "categories" in this ledger — they already
     * have their own dedicated P&L lines — so they never appear in this map. Best-effort, same
     * resilience convention as expenseTotalForMonth: a lookup failure leaves this month's
     * breakdown unknown rather than taking down the whole dashboard. */
    private Map<String, BigDecimal> expenseCategoryBreakdownForMonth(int year, int month) {
        try {
            YearMonth ym = YearMonth.of(year, month);
            LocalDate from = ym.atDay(1), to = ym.atEndOfMonth();
            Map<String, BigDecimal> breakdown = new LinkedHashMap<>(
                    expenseImports.isPeriodStatementCovered(from, to)
                            ? expenses.resolveStatementDerivedExpenseBreakdownByCategory(expenseImports.linkedExpenseEntryIds(from, to))
                            : expenses.resolveExpenseBreakdownByCategory(from, to));
            expenses.resolveCashBusinessExpenseBreakdownByCategory(from, to)
                    .forEach((k, v) -> breakdown.merge(k, v, BigDecimal::add));
            return breakdown;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Category breakdown of {@link #personalBankTotalForMonth} for a calendar month — same
     * statement-covered/manual split. Powers a per-month "what does Personal consist of" view on
     * the Net tab, since the owner can flag more than one category personal. Best-effort, same
     * resilience convention as expenseCategoryBreakdownForMonth. */
    private Map<String, BigDecimal> personalBreakdownForMonth(int year, int month) {
        try {
            YearMonth ym = YearMonth.of(year, month);
            LocalDate from = ym.atDay(1), to = ym.atEndOfMonth();
            return expenseImports.isPeriodStatementCovered(from, to)
                    ? expenses.resolveStatementDerivedPersonalBreakdownByCategory(expenseImports.linkedExpenseEntryIds(from, to))
                    : expenses.resolvePersonalBreakdownByCategory(from, to);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The bank account's real opening/closing balance for a calendar month (see {@code
     * ExpenseImportService.bankBalanceForMonth}) — actual cash movement, deliberately not expected
     * to reconcile against netRevenue (see that method's own doc comment on the payroll-timing
     * gap). Null when no completed import overlapping this month captured a balance. Best-effort,
     * same resilience convention as the other per-month resolvers. */
    private ExpenseImportService.BankBalance bankBalanceForMonth(int year, int month) {
        try {
            YearMonth ym = YearMonth.of(year, month);
            return expenseImports.bankBalanceForMonth(ym.atDay(1), ym.atEndOfMonth());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static BigDecimal netRevenue(BigDecimal gross, BigDecimal cardPayroll, BigDecimal cashPayroll,
                                          BigDecimal expenseTotal, BigDecimal cashBusinessExpenseTotal,
                                          BigDecimal managerLaborCost) {
        if (gross == null || cardPayroll == null || cashPayroll == null || expenseTotal == null
                || cashBusinessExpenseTotal == null || managerLaborCost == null) return null;
        return gross.subtract(cardPayroll).subtract(cashPayroll).subtract(expenseTotal)
                .subtract(cashBusinessExpenseTotal).subtract(managerLaborCost)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal profitAfterPersonal(BigDecimal netProfit, BigDecimal personalBankTotal,
                                                   BigDecimal ownerDrawsTotal) {
        if (netProfit == null || personalBankTotal == null || ownerDrawsTotal == null) return null;
        return netProfit.subtract(personalBankTotal).subtract(ownerDrawsTotal).setScale(2, RoundingMode.HALF_UP);
    }

    /** ymKey → [distinct clients seen, returning clients] for the range, from the visit ledger. */
    private Map<String, int[]> clientCountsByMonth(int fy, int fm, int ty, int tm) {
        Map<String, int[]> out = new HashMap<>();
        try {
            RetentionSeries s = retention.series(fy, fm, ty, tm, null); // null = whole salon
            for (RetentionSeries.SeriesPoint p : s.points()) {
                out.put(ymKey(p.year(), p.month()), new int[]{p.clientsSeen(), p.returningClients()});
            }
        } catch (RuntimeException e) {
            // Ledger unavailable / empty → no counts; the UI shows these months as unknown.
        }
        return out;
    }

    // --- prior period totals (same range, prior year) ---

    private YearTotals prevPeriodTotals(int fromYear, int fromMonth, int toYear, int toMonth) {
        List<int[]> range = buildRange(fromYear, fromMonth, toYear, toMonth);
        Set<Integer> yearsNeeded = range.stream().map(ym -> ym[0]).collect(Collectors.toSet());
        BigDecimal card = BigDecimal.ZERO, cash = BigDecimal.ZERO;
        Long businessId = currentBusinessContext.id();
        for (int yr : yearsNeeded) {
            for (PayPeriod pp : payPeriods.findAllByBusinessIdAndYearOrderByMonthAscHalfAsc(businessId, yr)) {
                if (!inRange(range, pp.getYear(), pp.getMonth())) continue;
                for (PeriodEntry e : entries.findAllByPayPeriodId(pp.getId())) {
                    card = card.add(e.getCardTotal());
                    cash = cash.add(e.getCashTotal());
                }
            }
        }
        return new YearTotals(card.add(cash), card, cash);
    }

    // --- helpers ---

    private static List<int[]> buildRange(int fromYear, int fromMonth, int toYear, int toMonth) {
        List<int[]> out = new ArrayList<>();
        YearMonth cur = YearMonth.of(fromYear, fromMonth);
        YearMonth end = YearMonth.of(toYear, toMonth);
        while (!cur.isAfter(end) && out.size() < MAX_MONTHS) {
            out.add(new int[]{cur.getYear(), cur.getMonthValue()});
            cur = cur.plusMonths(1);
        }
        return out;
    }

    private static boolean inRange(List<int[]> range, int year, int month) {
        for (int[] ym : range) {
            if (ym[0] == year && ym[1] == month) return true;
        }
        return false;
    }

    private static String ymKey(int year, int month) {
        return year + "-" + month;
    }

    private static boolean isFuture(int year, int month, int currentYear, int currentMonth) {
        return year > currentYear || (year == currentYear && month > currentMonth);
    }

    private static MonthSummary emptyMonth(int year, int month) {
        return new MonthSummary(year, month, label(month), null, null, null, null, 0,
                null, null, null, false, 0, 0, null, null, null, false,
                null, null, null, null, null, null, null, null, null);
    }

    /** Whether a COMPLETED bank-statement reconciliation overlaps this month (see
     * ExpenseImportService.isPeriodStatementCovered) — surfaced on MonthSummary so the Net tab can
     * tell the owner which months are real bank-statement numbers vs. estimates. Same best-effort
     * resilience convention as expenseTotalForMonth/managerLaborCostForMonth: a lookup failure just
     * means this month is treated as not-covered (estimates shown), not a dashboard-wide failure. */
    private boolean statementCoveredForMonth(int year, int month) {
        try {
            YearMonth ym = YearMonth.of(year, month);
            return expenseImports.isPeriodStatementCovered(ym.atDay(1), ym.atEndOfMonth());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String label(int month) {
        return Month.of(month).getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
    }

    private static BigDecimal avg(BigDecimal gross, int procedures) {
        if (procedures == 0 || gross == null) return null;
        return gross.divide(BigDecimal.valueOf(procedures), 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal pct(BigDecimal part, BigDecimal whole) {
        if (part == null || whole == null || whole.signum() == 0) return null;
        return part.multiply(BigDecimal.valueOf(100))
                   .divide(whole, 1, RoundingMode.HALF_UP);
    }

    private static final class ProviderAcc {
        final Long providerId;
        final String name;
        BigDecimal gross   = BigDecimal.ZERO;
        BigDecimal payroll = BigDecimal.ZERO;

        ProviderAcc(Long providerId, String name) {
            this.providerId = providerId;
            this.name = name;
        }
    }
}
