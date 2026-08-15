package com.salonreview.square;

import com.salonreview.domain.ProviderVisit;
import com.salonreview.repo.ProviderVisitRepository;
import com.salonreview.web.dto.RetentionReport;
import com.salonreview.web.dto.RetentionReport.ProviderRetentionRow;
import com.salonreview.web.dto.RetentionReport.RetentionTrendPoint;
import com.salonreview.web.dto.RetentionSeries;
import com.salonreview.web.dto.RetentionSeries.ProviderOption;
import com.salonreview.web.dto.RetentionSeries.SeriesPoint;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Computes per-provider retention from the {@link ProviderVisit} ledger, entirely in memory — for a
 * single salon the ledger is small, and "first-ever visit" classification needs the full history,
 * which is simplest (and most testable) to do over the whole list rather than in SQL. New/returning,
 * cohort retention (provider + salon) with a maturity gate, same-day rebook, and a short trend.
 */
@Service
public class RetentionAnalyticsService {

    static final int RETENTION_WINDOW_DAYS = 60; // K — one nail rebook cycle; configurable later
    private static final int TREND_MONTHS = 6;
    private static final int LEAK_MIN_NEW = 3;            // "many" fresh clients
    private static final BigDecimal LEAK_RETENTION = new BigDecimal("0.40"); // below this = leaky

    private final ProviderVisitRepository repo;
    private final SquareClient square;
    private final com.salonreview.config.CurrentBusinessContext currentBusinessContext;

    public RetentionAnalyticsService(ProviderVisitRepository repo, SquareClient square,
                                     com.salonreview.config.CurrentBusinessContext currentBusinessContext) {
        this.repo = repo;
        this.square = square;
        this.currentBusinessContext = currentBusinessContext;
    }

    public RetentionReport report(int year, int month) {
        List<ProviderVisit> all = repo.findAllByBusinessIdOrderByServiceDateAsc(currentBusinessContext.id());
        YearMonth ym = YearMonth.of(year, month);
        LocalDate mStart = ym.atDay(1), mEnd = ym.atEndOfMonth();
        LocalDate today = LocalDate.now(salonZone());

        // First-ever dates (need the whole ledger to be correct).
        Map<String, LocalDate> firstWithProvider = new HashMap<>(); // "customer|provider" -> first date
        Map<String, LocalDate> firstAtSalon = new HashMap<>();       // customer -> first salon date
        Map<String, String> firstSalonProvider = new HashMap<>();    // customer -> provider of first salon visit
        // Visit dates per (customer,provider) and per customer, for return-window checks.
        Map<String, List<LocalDate>> datesByCustProv = new HashMap<>();
        Map<String, List<LocalDate>> datesByCust = new HashMap<>();
        Map<String, String> latestProviderName = new HashMap<>();
        for (ProviderVisit v : all) { // already sorted by service_date asc
            String cp = v.getCustomerId() + "|" + v.getProviderRef();
            firstWithProvider.putIfAbsent(cp, v.getServiceDate());
            if (!firstAtSalon.containsKey(v.getCustomerId())) {
                firstAtSalon.put(v.getCustomerId(), v.getServiceDate());
                firstSalonProvider.put(v.getCustomerId(), v.getProviderRef());
            }
            datesByCustProv.computeIfAbsent(cp, k -> new ArrayList<>()).add(v.getServiceDate());
            datesByCust.computeIfAbsent(v.getCustomerId(), k -> new ArrayList<>()).add(v.getServiceDate());
            if (v.getProviderName() != null) latestProviderName.put(v.getProviderRef(), v.getProviderName());
        }

        // Providers active this month.
        List<ProviderVisit> inMonth = all.stream()
                .filter(v -> !v.getServiceDate().isBefore(mStart) && !v.getServiceDate().isAfter(mEnd))
                .toList();
        List<String> providers = inMonth.stream().map(ProviderVisit::getProviderRef).distinct().sorted().toList();

        boolean matured = !today.isBefore(mEnd.plusDays(RETENTION_WINDOW_DAYS));

        List<ProviderRetentionRow> rows = new ArrayList<>();
        for (String p : providers) {
            Set<String> seen = new java.util.LinkedHashSet<>();
            int rebooked = 0, visitCount = 0;
            for (ProviderVisit v : inMonth) {
                if (!v.getProviderRef().equals(p)) continue;
                seen.add(v.getCustomerId());
                visitCount++;
                if (v.isRebookedSameDay()) rebooked++;
            }

            List<String> cohort = new ArrayList<>(); // new-to-provider customers this month
            int newToSalonViaP = 0;
            for (String c : seen) {
                LocalDate fwp = firstWithProvider.get(c + "|" + p);
                if (inWindow(fwp, mStart, mEnd)) cohort.add(c);
                if (inWindow(firstAtSalon.get(c), mStart, mEnd) && p.equals(firstSalonProvider.get(c))) {
                    newToSalonViaP++;
                }
            }
            int clientsSeen = seen.size();
            int newToProvider = cohort.size();

            // Cohort retention within K days of each member's first visit with P.
            BigDecimal provRet = null, salonRet = null;
            if (matured && !cohort.isEmpty()) {
                int retP = 0, retS = 0;
                for (String c : cohort) {
                    LocalDate first = firstWithProvider.get(c + "|" + p);
                    if (returnedWithin(datesByCustProv.get(c + "|" + p), first)) retP++;
                    if (returnedWithin(datesByCust.get(c), first)) retS++;
                }
                provRet = ratio(retP, cohort.size());
                salonRet = ratio(retS, cohort.size());
            }

            boolean leak = matured && newToSalonViaP >= LEAK_MIN_NEW
                    && provRet != null && provRet.compareTo(LEAK_RETENTION) < 0;

            rows.add(new ProviderRetentionRow(
                    p, latestProviderName.getOrDefault(p, p),
                    clientsSeen, newToProvider, clientsSeen - newToProvider, newToSalonViaP,
                    visitCount == 0 ? null : ratio(rebooked, visitCount),
                    newToProvider, provRet, salonRet, matured, leak,
                    trend(all, p, ym)));
        }
        rows.sort(Comparator.comparing(ProviderRetentionRow::providerName, String.CASE_INSENSITIVE_ORDER));
        return new RetentionReport(year, month, RETENTION_WINDOW_DAYS, rows);
    }

    private static final int MAX_SERIES_MONTHS = 36;

    /**
     * New-vs-returning over a month range, for the whole salon (providerRef null → new = new-to-salon)
     * or one provider (new = new-to-that-provider). Plus the provider list for the selector.
     */
    public RetentionSeries series(int fromYear, int fromMonth, int toYear, int toMonth, String providerRef) {
        List<ProviderVisit> all = repo.findAllByBusinessIdOrderByServiceDateAsc(currentBusinessContext.id());
        String wanted = (providerRef == null || providerRef.isBlank()) ? null : providerRef;

        // First-ever dates (full history).
        Map<String, LocalDate> firstAtSalon = new HashMap<>();
        Map<String, LocalDate> firstWithProvider = new HashMap<>();
        Map<String, String> latestName = new LinkedHashMap<>();
        for (ProviderVisit v : all) {
            firstAtSalon.putIfAbsent(v.getCustomerId(), v.getServiceDate());
            firstWithProvider.putIfAbsent(v.getCustomerId() + "|" + v.getProviderRef(), v.getServiceDate());
            if (v.getProviderName() != null) latestName.put(v.getProviderRef(), v.getProviderName());
        }
        List<ProviderOption> providers = latestName.entrySet().stream()
                .map(e -> new ProviderOption(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(ProviderOption::name, String.CASE_INSENSITIVE_ORDER))
                .toList();

        YearMonth from = YearMonth.of(fromYear, fromMonth);
        YearMonth to = YearMonth.of(toYear, toMonth);
        if (to.isBefore(from)) { YearMonth t = from; from = to; to = t; }
        // Per month: distinct customers seen (filtered to the provider if any), split new vs returning.
        Map<YearMonth, Set<String>> seen = new LinkedHashMap<>();
        Map<YearMonth, Set<String>> fresh = new LinkedHashMap<>();
        for (ProviderVisit v : all) {
            if (wanted != null && !v.getProviderRef().equals(wanted)) continue;
            YearMonth m = YearMonth.from(v.getServiceDate());
            if (m.isBefore(from) || m.isAfter(to)) continue;
            seen.computeIfAbsent(m, k -> new java.util.HashSet<>()).add(v.getCustomerId());
            LocalDate firstDate = wanted == null
                    ? firstAtSalon.get(v.getCustomerId())
                    : firstWithProvider.get(v.getCustomerId() + "|" + wanted);
            if (firstDate != null && YearMonth.from(firstDate).equals(m)) {
                fresh.computeIfAbsent(m, k -> new java.util.HashSet<>()).add(v.getCustomerId());
            }
        }

        List<SeriesPoint> points = new ArrayList<>();
        YearMonth cursor = from;
        for (int i = 0; i < MAX_SERIES_MONTHS && !cursor.isAfter(to); i++, cursor = cursor.plusMonths(1)) {
            int s = seen.getOrDefault(cursor, Set.of()).size();
            int n = fresh.getOrDefault(cursor, Set.of()).size();
            points.add(new SeriesPoint(cursor.getYear(), cursor.getMonthValue(), s, n, s - n));
        }
        return new RetentionSeries(from.getYear(), from.getMonthValue(), to.getYear(), to.getMonthValue(),
                wanted, providers, points);
    }

    // --- helpers ---

    /** Last TREND_MONTHS months ending at ym: clients seen + new-to-provider for provider p. */
    private List<RetentionTrendPoint> trend(List<ProviderVisit> all, String p, YearMonth ym) {
        // first-visit-with-p is global; recompute lazily here from the full list for correctness.
        Map<String, LocalDate> firstWithP = new HashMap<>();
        for (ProviderVisit v : all) {
            if (v.getProviderRef().equals(p)) firstWithP.putIfAbsent(v.getCustomerId(), v.getServiceDate());
        }
        Map<YearMonth, Set<String>> seenByMonth = new TreeMap<>();
        Map<YearMonth, Set<String>> newByMonth = new TreeMap<>();
        for (ProviderVisit v : all) {
            if (!v.getProviderRef().equals(p)) continue;
            YearMonth m = YearMonth.from(v.getServiceDate());
            seenByMonth.computeIfAbsent(m, k -> new java.util.HashSet<>()).add(v.getCustomerId());
            if (YearMonth.from(firstWithP.get(v.getCustomerId())).equals(m)) {
                newByMonth.computeIfAbsent(m, k -> new java.util.HashSet<>()).add(v.getCustomerId());
            }
        }
        List<RetentionTrendPoint> out = new ArrayList<>();
        for (int i = TREND_MONTHS - 1; i >= 0; i--) {
            YearMonth m = ym.minusMonths(i);
            out.add(new RetentionTrendPoint(m.getYear(), m.getMonthValue(),
                    seenByMonth.getOrDefault(m, Set.of()).size(),
                    newByMonth.getOrDefault(m, Set.of()).size()));
        }
        return out;
    }

    private static boolean inWindow(LocalDate d, LocalDate start, LocalDate end) {
        return d != null && !d.isBefore(start) && !d.isAfter(end);
    }

    /** Any visit strictly after {@code first} and within K days of it. */
    private static boolean returnedWithin(List<LocalDate> dates, LocalDate first) {
        if (dates == null || first == null) return false;
        LocalDate limit = first.plusDays(RETENTION_WINDOW_DAYS);
        for (LocalDate d : dates) {
            if (d.isAfter(first) && !d.isAfter(limit)) return true;
        }
        return false;
    }

    private static BigDecimal ratio(int num, int den) {
        if (den == 0) return null;
        return BigDecimal.valueOf(num).divide(BigDecimal.valueOf(den), 4, RoundingMode.HALF_UP);
    }

    private ZoneId salonZone() {
        try {
            String tz = square.locationTimeZone();
            return tz != null && !tz.isBlank() ? ZoneId.of(tz) : ZoneOffset.UTC;
        } catch (RuntimeException e) {
            return ZoneOffset.UTC;
        }
    }
}
