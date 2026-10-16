package com.salonreview.marketing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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

    @Test
    @DisplayName("source-filtered attribution query builds (the LIKE '...-%' must survive .formatted)")
    void filteredAttributionQueryBuilds() {
        // The Overview's default filter is ads-only, so this branch is the one every page load hits;
        // a bare % inside the .formatted() template threw UnknownFormatConversionException and the
        // whole Overview showed "not available" for every business (2026-10-09 .. 10-16).
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        new MarketingDashboardRepository(jdbc).findAttributedBookingRows(
                UUID.randomUUID(), null, null, TrafficSourceSql.ADS_ONLY);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getValue()).contains("NOT LIKE 'four-hand-request-%'");
    }
}
