package com.salonreview.square;

import com.salonreview.domain.Half;
import com.salonreview.domain.PayPeriod;
import com.salonreview.domain.PeriodEntry;
import com.salonreview.domain.RevenueSnapshot;
import com.salonreview.repo.PayPeriodRepository;
import com.salonreview.repo.PeriodEntryRepository;
import com.salonreview.repo.RevenueSnapshotRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Month-end revenue forecaster blending two independent techniques:
 *
 * <ol>
 *   <li><b>Pattern matching</b> uses existing {@link PeriodEntry} half-month totals. For each of the
 *       last 6 settled months we compute {@code firstHalfRatio = firstHalfTotal / monthTotal}, take
 *       the average, and project {@code currentMTD / avgRatio}. Works from day one of the deploy —
 *       no warm-up needed.</li>
 *   <li><b>Booking-ceiling calibration</b> uses past snapshots (where {@code month_end_actual} has
 *       been filled in) to learn the typical bias between the naive ceiling
 *       {@code MTD + upcomingGross} and the actual month-end. Needs at least 3 closed snapshots; once
 *       available, contributes more weight as it accumulates.</li>
 * </ol>
 *
 * The two signals are blended by the weights in {@link #blend} and bracketed into a range. With only
 * one technique available the range is {@code mid * 0.85 → mid * 1.15}; with both, the range stretches
 * to {@code min*0.9 → max*1.1} so wider disagreement shows up as a wider range — itself a signal that
 * this month looks atypical.
 */
@Service
public class RevenueForecastService {

    private static final int PATTERN_WINDOW_MONTHS    = 6;
    private static final int MIN_PATTERN_MONTHS       = 3;
    private static final int CALIBRATION_WINDOW_ROWS  = 6;
    private static final int MIN_CALIBRATION_ROWS     = 3;

    private final PayPeriodRepository payPeriods;
    private final PeriodEntryRepository entries;
    private final RevenueSnapshotRepository snapshots;
    private final com.salonreview.config.CurrentBusinessContext currentBusinessContext;

    public RevenueForecastService(PayPeriodRepository payPeriods,
                                  PeriodEntryRepository entries,
                                  RevenueSnapshotRepository snapshots,
                                  com.salonreview.config.CurrentBusinessContext currentBusinessContext) {
        this.payPeriods = payPeriods;
        this.entries = entries;
        this.snapshots = snapshots;
        this.currentBusinessContext = currentBusinessContext;
    }

    public ForecastResult forecast(int year, int month, BigDecimal currentMTD, BigDecimal upcomingGross) {
        BigDecimal naive = currentMTD.add(upcomingGross == null ? BigDecimal.ZERO : upcomingGross)
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal pattern     = patternMatch(year, month, currentMTD);
        int historyMonths      = countSettledMonths(year, month);
        BigDecimal calibration = calibration(currentMTD, upcomingGross);
        int calibrationPoints  = countUsableCalibrationRows();

        return blend(pattern, calibration, naive, historyMonths, calibrationPoints);
    }

    // --- pattern matching ---

    /**
     * Average {@code firstHalfRatio} over the last {@value PATTERN_WINDOW_MONTHS} settled months
     * (excluding the requested month). Returns {@code null} when fewer than {@value MIN_PATTERN_MONTHS}
     * usable months are available.
     */
    BigDecimal patternMatch(int year, int month, BigDecimal currentMTD) {
        List<YearMonth> settled = recentSettledMonthsBefore(year, month, PATTERN_WINDOW_MONTHS);
        if (settled.size() < MIN_PATTERN_MONTHS) return null;

        BigDecimal ratioSum = BigDecimal.ZERO;
        int used = 0;
        for (YearMonth ym : settled) {
            BigDecimal first  = halfTotal(ym, Half.FIRST);
            BigDecimal second = halfTotal(ym, Half.SECOND);
            BigDecimal total  = first.add(second);
            if (total.signum() <= 0 || first.signum() <= 0) continue;
            ratioSum = ratioSum.add(first.divide(total, 6, RoundingMode.HALF_UP));
            used++;
        }
        if (used < MIN_PATTERN_MONTHS) return null;
        BigDecimal avgRatio = ratioSum.divide(BigDecimal.valueOf(used), 6, RoundingMode.HALF_UP);
        if (avgRatio.signum() <= 0) return null;

        return currentMTD.divide(avgRatio, 2, RoundingMode.HALF_UP);
    }

    /** Months with both halves having at least one PeriodEntry, ordered newest → oldest, before {@code (year, month)}. */
    private List<YearMonth> recentSettledMonthsBefore(int year, int month, int limit) {
        YearMonth requested = YearMonth.of(year, month);
        // Walk back up to 24 months looking for those with data; bounded so we stop in a brand-new salon.
        List<YearMonth> out = new ArrayList<>();
        YearMonth cursor = requested.minusMonths(1);
        for (int steps = 0; steps < 24 && out.size() < limit; steps++) {
            if (hasBothHalvesSettled(cursor)) out.add(cursor);
            cursor = cursor.minusMonths(1);
        }
        return out;
    }

    private boolean hasBothHalvesSettled(YearMonth ym) {
        boolean first = false, second = false;
        for (PayPeriod pp : payPeriods.findAllByBusinessIdAndYearOrderByMonthAscHalfAsc(currentBusinessContext.id(), ym.getYear())) {
            if (pp.getMonth() != ym.getMonthValue()) continue;
            if (entries.findAllByPayPeriodId(pp.getId()).isEmpty()) continue;
            if (pp.getHalf() == Half.FIRST) first = true;
            if (pp.getHalf() == Half.SECOND) second = true;
        }
        return first && second;
    }

    private BigDecimal halfTotal(YearMonth ym, Half half) {
        BigDecimal total = BigDecimal.ZERO;
        for (PayPeriod pp : payPeriods.findAllByBusinessIdAndYearOrderByMonthAscHalfAsc(currentBusinessContext.id(), ym.getYear())) {
            if (pp.getMonth() != ym.getMonthValue() || pp.getHalf() != half) continue;
            for (PeriodEntry e : entries.findAllByPayPeriodId(pp.getId())) {
                total = total.add(e.getCardTotal()).add(e.getCashTotal());
            }
        }
        return total;
    }

    int countSettledMonths(int year, int month) {
        return recentSettledMonthsBefore(year, month, 24).size();
    }

    // --- calibration ---

    /**
     * Mean of {@code month_end_actual / (mtd_revenue + upcoming_gross)} over the most recent up-to-6
     * snapshot rows with a non-null {@code month_end_actual}, multiplied by today's naive ceiling.
     * Returns null when fewer than {@value MIN_CALIBRATION_ROWS} usable rows exist.
     */
    BigDecimal calibration(BigDecimal currentMTD, BigDecimal upcomingGross) {
        List<RevenueSnapshot> rows = snapshots.findAllByMonthEndActualIsNotNullOrderBySnapshotDateDesc(
                PageRequest.of(0, CALIBRATION_WINDOW_ROWS));
        // Only one snapshot per month is needed — pick the latest by snapshot_date per month to avoid
        // a long-running month with daily snapshots dominating the average.
        Map<YearMonth, RevenueSnapshot> oneByMonth = new HashMap<>();
        for (RevenueSnapshot r : rows) {
            YearMonth ym = YearMonth.from(r.getSnapshotDate());
            oneByMonth.merge(ym, r, (existing, candidate) ->
                    candidate.getSnapshotDate().isAfter(existing.getSnapshotDate()) ? candidate : existing);
        }
        if (oneByMonth.size() < MIN_CALIBRATION_ROWS) return null;

        BigDecimal sum = BigDecimal.ZERO;
        int used = 0;
        for (RevenueSnapshot r : oneByMonth.values()) {
            BigDecimal projected = r.getMtdRevenue().add(r.getUpcomingGross());
            if (projected.signum() <= 0) continue;
            BigDecimal ratio = r.getMonthEndActual().divide(projected, 6, RoundingMode.HALF_UP);
            sum = sum.add(ratio);
            used++;
        }
        if (used < MIN_CALIBRATION_ROWS) return null;

        BigDecimal meanBias = sum.divide(BigDecimal.valueOf(used), 6, RoundingMode.HALF_UP);
        BigDecimal naive = currentMTD.add(upcomingGross == null ? BigDecimal.ZERO : upcomingGross);
        return naive.multiply(meanBias).setScale(2, RoundingMode.HALF_UP);
    }

    int countUsableCalibrationRows() {
        // Distinct months among the recent rows with month_end_actual filled.
        List<RevenueSnapshot> rows = snapshots.findAllByMonthEndActualIsNotNullOrderBySnapshotDateDesc(
                PageRequest.of(0, CALIBRATION_WINDOW_ROWS));
        Map<YearMonth, Boolean> months = new HashMap<>();
        for (RevenueSnapshot r : rows) months.put(YearMonth.from(r.getSnapshotDate()), true);
        return months.size();
    }

    // --- blend ---

    ForecastResult blend(BigDecimal pattern, BigDecimal calibration, BigDecimal naive,
                         int historyMonths, int calibrationPoints) {
        boolean havePattern     = pattern     != null;
        boolean haveCalibration = calibration != null;

        // Cold start: no usable signal — fall back to naive ceiling with no range.
        if (!havePattern && !haveCalibration) {
            return new ForecastResult(naive, null, null, calibrationPoints, historyMonths);
        }

        // Single signal: ±15% range around it.
        if (havePattern ^ haveCalibration) {
            BigDecimal mid = havePattern ? pattern : calibration;
            return new ForecastResult(mid, scale(mid, "0.85"), scale(mid, "1.15"),
                    calibrationPoints, historyMonths);
        }

        // Both signals — weighted blend by calibration data count.
        BigDecimal[] weights = weightsFor(calibrationPoints); // [pattern, calibration]
        BigDecimal mid = pattern.multiply(weights[0]).add(calibration.multiply(weights[1]))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal lo  = scale(min(pattern, calibration), "0.9");
        BigDecimal hi  = scale(max(pattern, calibration), "1.1");
        return new ForecastResult(mid, lo, hi, calibrationPoints, historyMonths);
    }

    /**
     * Only reached when calibration is non-null, which requires >= MIN_CALIBRATION_ROWS (3).
     * 3-5 months → 50/50 split; 6+ → calibration dominates 70/30.
     */
    private static BigDecimal[] weightsFor(int calPoints) {
        if (calPoints <= 5)  return new BigDecimal[]{ bd("0.5"), bd("0.5") };
        return new BigDecimal[]{ bd("0.3"), bd("0.7") };
    }

    private static BigDecimal scale(BigDecimal v, String mult) {
        return v.multiply(bd(mult)).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal min(BigDecimal a, BigDecimal b) { return a.compareTo(b) <= 0 ? a : b; }
    private static BigDecimal max(BigDecimal a, BigDecimal b) { return a.compareTo(b) >= 0 ? a : b; }

    private static BigDecimal bd(String s) { return new BigDecimal(s); }
}
