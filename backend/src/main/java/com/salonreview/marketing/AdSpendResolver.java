package com.salonreview.marketing;

import com.salonreview.domain.AdSpendEntry;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves ad spend for an arbitrary [from, to] report range from the flexible per-page
 * {@code ad_spend_entries} ledger (see openspec/changes/ads-report-consolidation/design.md D2).
 * Each overlapping entry contributes {@code amount * (days of overlap with [from, to] / entry's
 * own total days)} — a whole-month entry viewed one week at a time is prorated down to that
 * week's share; a week entry entirely inside a requested month contributes its whole amount.
 *
 * <p>The result is flagged {@code exact} only when every day in {@code [from, to]} is covered by
 * precisely one entry and that entry isn't clipped at either edge — i.e. the naive sum needed no
 * proration at all (several entries that exactly tile a month with no gaps still count as exact).
 * Anything else (a gap, an overlap between entries, or a clipped entry) is flagged {@code
 * estimated}, the same meaning {@code PeriodRow.adSpendEstimated} already carried.
 */
final class AdSpendResolver {

    private AdSpendResolver() {}

    record Resolved(BigDecimal amount, boolean estimated) {}

    static Resolved resolve(List<AdSpendEntry> entries, LocalDate from, LocalDate to) {
        BigDecimal zero = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        if (entries.isEmpty() || to.isBefore(from)) return new Resolved(zero, false);

        BigDecimal total = BigDecimal.ZERO;
        Map<LocalDate, Integer> dayCoverage = new HashMap<>();
        boolean anyClipped = false;

        for (AdSpendEntry e : entries) {
            LocalDate overlapStart = e.getPeriodStart().isBefore(from) ? from : e.getPeriodStart();
            LocalDate overlapEnd = e.getPeriodEnd().isAfter(to) ? to : e.getPeriodEnd();
            if (overlapEnd.isBefore(overlapStart)) continue; // caller's overlap query shouldn't produce this

            if (e.getPeriodStart().isBefore(from) || e.getPeriodEnd().isAfter(to)) anyClipped = true;

            long entryTotalDays = ChronoUnit.DAYS.between(e.getPeriodStart(), e.getPeriodEnd()) + 1;
            long overlapDays = ChronoUnit.DAYS.between(overlapStart, overlapEnd) + 1;
            BigDecimal contribution = e.getAmountSpent()
                    .multiply(BigDecimal.valueOf(overlapDays))
                    .divide(BigDecimal.valueOf(entryTotalDays), 10, RoundingMode.HALF_UP);
            total = total.add(contribution);

            for (LocalDate day = overlapStart; !day.isAfter(overlapEnd); day = day.plusDays(1)) {
                dayCoverage.merge(day, 1, Integer::sum);
            }
        }

        long daysInRange = ChronoUnit.DAYS.between(from, to) + 1;
        boolean noGaps = dayCoverage.size() == daysInRange;
        boolean noOverlaps = dayCoverage.values().stream().allMatch(count -> count == 1);
        boolean exact = noGaps && noOverlaps && !anyClipped;

        return new Resolved(total.setScale(2, RoundingMode.HALF_UP), !exact);
    }
}
