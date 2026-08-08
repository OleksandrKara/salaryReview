package com.salonreview.square;

import com.salonreview.domain.PayPeriod;
import com.salonreview.domain.PeriodEntry;
import com.salonreview.domain.SalonConfig;
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

    public OwnerOverviewService(PayPeriodRepository payPeriods, PeriodEntryRepository entries,
                                CommissionCalculator calculator, SalonConfigRepository salonConfig,
                                SquareMonthAggregator aggregator, RetentionAnalyticsService retention,
                                ManualAdjustmentService manualAdjustments, ExpenseService expenses,
                                ManagerTimeService managerTime, ExpenseImportService expenseImports) {
        this.expenseImports = expenseImports;
        this.payPeriods = payPeriods;
        this.entries = entries;
        this.calculator = calculator;
        this.salonConfig = salonConfig;
        this.aggregator = aggregator;
        this.retention = retention;
        this.manualAdjustments = manualAdjustments;
        this.expenses = expenses;
        this.managerTime = managerTime;
    }

    public OwnerOverviewDto overview(int fromYear, int fromMonth, int toYear, int toMonth) {
        String key = fromYear + "-" + fromMonth + ":" + toYear + "-" + toMonth;
        return cache.get(key, CACHE_TTL, () -> computeOverview(fromYear, fromMonth, toYear, toMonth));
    }

    /** Drops every cached range — backs the global "Sync now" button (see SquareSyncController)
     * so an owner can force a fresh Square pull without waiting out the 30-day TTL. */
    public void invalidateCache() {
        cache.invalidateAll();
    }

    private OwnerOverviewDto computeOverview(int fromYear, int fromMonth, int toYear, int toMonth) {
        SalonConfig cfg = salonConfig.findById(1)
                .orElseThrow(() -> new IllegalStateException("Salon config with id=1 is missing"));

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
            for (PayPeriod pp : payPeriods.findAllByYearOrderByMonthAscHalfAsc(yr)) {
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
            CompletableFuture<?>[] futures = liveNeeded.stream()
                    .map(ym -> CompletableFuture.runAsync(
                            () -> liveResults.put(ymKey(ym[0], ym[1]), fromSquare(ym[1], ym[0], cfg))))
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
        payroll = payrollForMonth(year, month, payroll);
        BigDecimal expenseTotal = expenseTotalForMonth(year, month);
        BigDecimal managerLaborCost = managerLaborCostForMonth(year, month);
        return new MonthSummary(year, month, label(month), card, cash, gross, tips, procedures,
                avg(gross, procedures), payroll, pct(payroll, gross), true, 0, 0,
                expenseTotal, managerLaborCost, netRevenue(gross, payroll, expenseTotal, managerLaborCost));
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
            payroll = payrollForMonth(year, month, payroll);

            BigDecimal expenseTotal = expenseTotalForMonth(year, month);
            BigDecimal managerLaborCost = managerLaborCostForMonth(year, month);
            return new MonthSummary(year, month, label(month), card, cash, gross, tips, procedures,
                    avg(gross, procedures), payroll, pct(payroll, gross), false, 0, 0,
                    expenseTotal, managerLaborCost, netRevenue(gross, payroll, expenseTotal, managerLaborCost));
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

    /** Resolves this calendar month's provider commission (payroll): the formula/settlement-computed
     * figure the caller already worked out, unless a completed statement reconciliation covers the
     * month — in that case the reconciliation's own linked PROVIDER_PAYROLL entries are the
     * exclusive source instead (openspec design.md D12), the same real-disbursement-replaces-the-
     * estimate treatment {@link #managerLaborCostForMonth} already gets. Does not affect the
     * per-provider YTD breakdown ({@code ProviderAcc}/{@code ProviderYtd}), which has no way to
     * attribute a bank transaction to one specific provider and stays formula-based regardless. */
    private BigDecimal payrollForMonth(int year, int month, BigDecimal computed) {
        try {
            YearMonth ym = YearMonth.of(year, month);
            LocalDate from = ym.atDay(1), to = ym.atEndOfMonth();
            if (expenseImports.isPeriodStatementCovered(from, to)) {
                return expenses.resolveStatementDerivedProviderPayrollTotal(expenseImports.linkedExpenseEntryIds(from, to));
            }
            return computed;
        } catch (RuntimeException e) {
            return computed;
        }
    }

    private static BigDecimal netRevenue(BigDecimal gross, BigDecimal payroll, BigDecimal expenseTotal,
                                          BigDecimal managerLaborCost) {
        if (gross == null || payroll == null || expenseTotal == null || managerLaborCost == null) return null;
        return gross.subtract(payroll).subtract(expenseTotal).subtract(managerLaborCost)
                .setScale(2, RoundingMode.HALF_UP);
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
        for (int yr : yearsNeeded) {
            for (PayPeriod pp : payPeriods.findAllByYearOrderByMonthAscHalfAsc(yr)) {
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
                null, null, null, false, 0, 0, null, null, null);
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
