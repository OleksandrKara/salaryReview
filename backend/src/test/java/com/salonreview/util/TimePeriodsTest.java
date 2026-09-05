package com.salonreview.util;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class TimePeriodsTest {

    @Test
    void formatTimeSinceVariants() {
        LocalDate to = LocalDate.of(2026, 9, 5);
        assertThat(TimePeriods.formatTimeSince(LocalDate.of(2026, 2, 5), to)).isEqualTo("7 months");
        assertThat(TimePeriods.formatTimeSince(LocalDate.of(2025, 9, 5), to)).isEqualTo("1 year");
        assertThat(TimePeriods.formatTimeSince(LocalDate.of(2025, 2, 5), to)).isEqualTo("1 year and 7 months");
        assertThat(TimePeriods.formatTimeSince(LocalDate.of(2024, 9, 5), to)).isEqualTo("2 years");
        assertThat(TimePeriods.formatTimeSince(LocalDate.of(2026, 8, 5), to)).isEqualTo("1 month");
    }
}
