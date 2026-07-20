package com.salonreview.marketing;

import com.salonreview.domain.AdSpendEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AdSpendResolverTest {

    private static AdSpendEntry entry(LocalDate start, LocalDate end, double amount) {
        return AdSpendEntry.builder()
                .id(1L)
                .landingPageSlug("mani")
                .periodStart(start)
                .periodEnd(end)
                .amountSpent(BigDecimal.valueOf(amount))
                .enteredAt(Instant.now())
                .build();
    }

    @Test
    @DisplayName("no entries -> zero, not estimated")
    void noEntries() {
        var resolved = AdSpendResolver.resolve(List.of(), LocalDate.of(2026, 7, 13), LocalDate.of(2026, 7, 19));
        assertThat(resolved.amount()).isEqualByComparingTo("0.00");
        assertThat(resolved.estimated()).isFalse();
    }

    @Test
    @DisplayName("one entry exactly matching the requested range -> exact")
    void exactSingleEntry() {
        var e = entry(LocalDate.of(2026, 7, 13), LocalDate.of(2026, 7, 19), 455.59);
        var resolved = AdSpendResolver.resolve(List.of(e), LocalDate.of(2026, 7, 13), LocalDate.of(2026, 7, 19));
        assertThat(resolved.amount()).isEqualByComparingTo("455.59");
        assertThat(resolved.estimated()).isFalse();
    }

    @Test
    @DisplayName("requested range only partially overlaps a wider entry -> prorated, estimated")
    void partialOverlapIsEstimated() {
        // $738.53 across 15 days (Jul 5-19); requesting just the last 7 days (Jul 13-19).
        var e = entry(LocalDate.of(2026, 7, 5), LocalDate.of(2026, 7, 19), 738.53);
        var resolved = AdSpendResolver.resolve(List.of(e), LocalDate.of(2026, 7, 13), LocalDate.of(2026, 7, 19));
        // 738.53 * 7/15 = 344.6473... -> 344.65
        assertThat(resolved.amount()).isEqualByComparingTo("344.65");
        assertThat(resolved.estimated()).isTrue();
    }

    @Test
    @DisplayName("a whole-month entry viewed as one week within it is prorated by day fraction")
    void weekWithinMonth() {
        var e = entry(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), 620.00); // 31 days, $20/day
        var resolved = AdSpendResolver.resolve(List.of(e), LocalDate.of(2026, 7, 13), LocalDate.of(2026, 7, 19)); // 7 days
        assertThat(resolved.amount()).isEqualByComparingTo("140.00");
        assertThat(resolved.estimated()).isTrue();
    }

    @Test
    @DisplayName("two entries exactly tiling the requested range with no gaps/overlap -> exact, summed")
    void exactlyTilingEntriesSumExact() {
        var week1 = entry(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 15), 300.00);
        var week2 = entry(LocalDate.of(2026, 7, 16), LocalDate.of(2026, 7, 31), 400.00);
        var resolved = AdSpendResolver.resolve(List.of(week1, week2), LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));
        assertThat(resolved.amount()).isEqualByComparingTo("700.00");
        assertThat(resolved.estimated()).isFalse();
    }

    @Test
    @DisplayName("a gap (no entry for some days) is estimated")
    void gapIsEstimated() {
        var e = entry(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 10), 100.00);
        var resolved = AdSpendResolver.resolve(List.of(e), LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 20));
        assertThat(resolved.estimated()).isTrue();
    }

    @Test
    @DisplayName("two entries covering the same day (a correction) are treated as estimated, not silently double-counted away")
    void overlappingEntriesAreEstimated() {
        var original = entry(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 7), 100.00);
        var correction = entry(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 7), 150.00);
        var resolved = AdSpendResolver.resolve(List.of(original, correction), LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 7));
        assertThat(resolved.amount()).isEqualByComparingTo("250.00");
        assertThat(resolved.estimated()).isTrue();
    }
}
