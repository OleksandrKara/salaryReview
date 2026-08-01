package com.salonreview.square;

import com.salonreview.domain.ExpenseEntry;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Resolves total business expenses for an arbitrary [from, to] range from the flexible
 * {@code expense_entries} ledger — same day-overlap proration as {@code AdSpendResolver}: each
 * overlapping entry contributes {@code amount * (days of overlap with [from, to] / entry's own
 * total days)}, so a whole-month entry viewed one week at a time is prorated down to that week's
 * share.
 */
final class ExpenseResolver {

    private ExpenseResolver() {}

    static BigDecimal resolve(List<ExpenseEntry> entries, LocalDate from, LocalDate to) {
        BigDecimal zero = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        if (entries.isEmpty() || to.isBefore(from)) return zero;

        BigDecimal total = BigDecimal.ZERO;
        for (ExpenseEntry e : entries) {
            LocalDate overlapStart = e.getPeriodStart().isBefore(from) ? from : e.getPeriodStart();
            LocalDate overlapEnd = e.getPeriodEnd().isAfter(to) ? to : e.getPeriodEnd();
            if (overlapEnd.isBefore(overlapStart)) continue; // caller's overlap query shouldn't produce this

            long entryTotalDays = ChronoUnit.DAYS.between(e.getPeriodStart(), e.getPeriodEnd()) + 1;
            long overlapDays = ChronoUnit.DAYS.between(overlapStart, overlapEnd) + 1;
            BigDecimal contribution = e.getAmount()
                    .multiply(BigDecimal.valueOf(overlapDays))
                    .divide(BigDecimal.valueOf(entryTotalDays), 10, RoundingMode.HALF_UP);
            total = total.add(contribution);
        }

        return total.setScale(2, RoundingMode.HALF_UP);
    }
}
