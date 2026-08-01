package com.salonreview.square;

import com.salonreview.domain.ExpenseEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExpenseResolverTest {

    private static ExpenseEntry entry(LocalDate start, LocalDate end, double amount) {
        return ExpenseEntry.builder()
                .id(1L)
                .category(ExpenseEntry.CATEGORY_MATERIALS)
                .periodStart(start)
                .periodEnd(end)
                .amount(BigDecimal.valueOf(amount))
                .enteredAt(Instant.now())
                .build();
    }

    @Test
    @DisplayName("no entries -> zero")
    void noEntries() {
        BigDecimal resolved = ExpenseResolver.resolve(List.of(), LocalDate.of(2026, 7, 13), LocalDate.of(2026, 7, 19));
        assertThat(resolved).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("one entry exactly matching the requested range -> full amount")
    void exactSingleEntry() {
        var e = entry(LocalDate.of(2026, 7, 13), LocalDate.of(2026, 7, 19), 455.59);
        BigDecimal resolved = ExpenseResolver.resolve(List.of(e), LocalDate.of(2026, 7, 13), LocalDate.of(2026, 7, 19));
        assertThat(resolved).isEqualByComparingTo("455.59");
    }

    @Test
    @DisplayName("a whole-month entry viewed as one week within it is prorated by day fraction")
    void weekWithinMonth() {
        var e = entry(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), 620.00); // 31 days, $20/day
        BigDecimal resolved = ExpenseResolver.resolve(List.of(e), LocalDate.of(2026, 7, 13), LocalDate.of(2026, 7, 19)); // 7 days
        assertThat(resolved).isEqualByComparingTo("140.00");
    }

    @Test
    @DisplayName("two entries exactly tiling the requested range are summed")
    void tilingEntriesSum() {
        var week1 = entry(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 15), 300.00);
        var week2 = entry(LocalDate.of(2026, 7, 16), LocalDate.of(2026, 7, 31), 400.00);
        BigDecimal resolved = ExpenseResolver.resolve(List.of(week1, week2), LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));
        assertThat(resolved).isEqualByComparingTo("700.00");
    }

    @Test
    @DisplayName("an entry entirely outside the requested range contributes nothing")
    void entryOutsideRangeContributesNothing() {
        var e = entry(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), 500.00);
        BigDecimal resolved = ExpenseResolver.resolve(List.of(e), LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));
        assertThat(resolved).isEqualByComparingTo("0.00");
    }
}
