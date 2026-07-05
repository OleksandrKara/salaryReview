package com.salonreview.marketing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Regression coverage for a real production incident: findStatsSince crashed with a
 * NullPointerException on every landing page that had no cutoff set (the default state),
 * because Stream.findFirst() itself calls Optional.of(element) internally and throws the
 * instant it reaches a null element — filter() must run first, not after.
 */
class MarketingDashboardRepositoryTest {

    @Test
    @DisplayName("a single null row (no cutoff set) yields empty, not an NPE")
    void nullRowYieldsEmptyWithoutThrowing() {
        Optional<Instant> result = MarketingDashboardRepository.firstNonNullInstant(Collections.singletonList(null));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("no rows at all (landing page not found) yields empty")
    void noRowsYieldsEmpty() {
        Optional<Instant> result = MarketingDashboardRepository.firstNonNullInstant(Collections.emptyList());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("a real timestamp is converted to the matching Instant")
    void realTimestampIsConverted() {
        Instant expected = Instant.parse("2026-07-10T09:00:00Z");

        Optional<Instant> result = MarketingDashboardRepository.firstNonNullInstant(
                Arrays.asList(Timestamp.from(expected)));

        assertThat(result).contains(expected);
    }
}
