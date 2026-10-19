package com.salonreview.marketing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AdsConversionFeedServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-20T18:00:00Z");
    private static final Instant CLICK = Instant.parse("2026-10-01T17:00:00Z");

    private static AdsConversionFeedService.Click click(String service, String status, Instant start) {
        return new AdsConversionFeedService.Click("GCLID1", CLICK, service, status, start, "C1", "Jane.Doe+ads@Gmail.com");
    }

    private static AdsConversionFeedService.Payment pay(String at, String amount) {
        return new AdsConversionFeedService.Payment(Instant.parse(at), new BigDecimal(amount));
    }

    @Test
    @DisplayName("attended consultation, then deposit + procedure: one consultation row, one procedure row when paid reaches $150")
    void consultationThenProcedure() {
        var rows = AdsConversionFeedService.conversionsFor(
                click("Online Consultation", "ACCEPTED", Instant.parse("2026-10-03T18:00:00Z")),
                List.of(pay("2026-10-04T18:00:00Z", "50.00"), pay("2026-10-15T20:00:00Z", "400.00"), pay("2026-10-18T20:00:00Z", "30.00")),
                NOW);
        assertThat(rows).extracting(AdsConversionFeedService.Row::name)
                .containsExactly(AdsConversionFeedService.CONSULTATION, AdsConversionFeedService.PROCEDURE);
        assertThat(rows.get(1).value()).isEqualByComparingTo("450.00");
        assertThat(rows.get(1).at()).isEqualTo(Instant.parse("2026-10-15T20:00:00Z"));
    }

    @Test
    @DisplayName("no-show, cancelled or future consultation is not reported; a deposit alone is not a procedure")
    void notAttendedNothing() {
        assertThat(AdsConversionFeedService.conversionsFor(click("Online Consultation", "NO_SHOW", Instant.parse("2026-10-03T18:00:00Z")),
                List.of(pay("2026-10-04T18:00:00Z", "50.00")), NOW)).isEmpty();
        assertThat(AdsConversionFeedService.conversionsFor(click("Online Consultation", "ACCEPTED", Instant.parse("2026-10-25T18:00:00Z")),
                List.of(), NOW)).isEmpty();
    }

    @Test
    @DisplayName("email is normalized and SHA-256 hashed the way Google asks")
    void hashing() {
        assertThat(AdsConversionFeedService.hashEmail("Jane.Doe+ads@Gmail.com"))
                .isEqualTo(AdsConversionFeedService.hashEmail("janedoe@gmail.com"))
                .hasSize(64);
    }

    @Test
    @DisplayName("CSV: timezone header, Ads column names, value + currency only on the procedure row")
    void render() {
        var rows = AdsConversionFeedService.conversionsFor(
                click("In-Person Consultation", "ACCEPTED", Instant.parse("2026-10-03T18:00:00Z")),
                List.of(pay("2026-10-15T20:00:00Z", "350.00")), NOW);
        String csv = AdsConversionFeedService.render(rows);
        assertThat(csv).startsWith("Parameters:TimeZone=America/Los_Angeles\nGoogle Click ID,Conversion Name,Conversion Time,");
        assertThat(csv).contains("GCLID1,PMU Consultation Attended,2026-10-03 11:00:00,,,");
        assertThat(csv).contains("GCLID1,PMU Procedure Paid,2026-10-15 13:00:00,350.00,USD,");
        assertThat(csv).doesNotContain("Jane").doesNotContain("Phone");
    }
}
