package com.salonreview.sms;

import com.salonreview.sms.ProviderAvailabilityObservation.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.salonreview.sms.ProviderScheduleTestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

class ProviderAvailabilityLossDetectorTest {
    private final ProviderAvailabilityLossDetector detector = new ProviderAvailabilityLossDetector();
    private final Instant now = BASE.plus(Duration.ofMinutes(10));

    @Test void exactReportedTwoHourCutoffDoesNotBecomeAClosure() {
        Instant at = Instant.parse("2026-09-28T20:30:14.815266Z");
        Instant start = Instant.parse("2026-09-28T22:30:00Z");
        var before = observation(at.minus(Duration.ofMinutes(10)), contract(true), grid(start, 1), List.of(), List.of());
        var current = observation(at, contract(true), List.of(), List.of(), List.of());
        assertThat(detector.detect(before, current, null).reason()).isEqualTo("NATURAL_CUTOFF");
        assertThat(detector.detect(before, current, null).windows()).isEmpty();
    }

    @Test void largeContinuousLossQualifiesButOneStartDoesNotRepresentHours() {
        assertThat(detector.detect(observation(BASE, true, true), observation(now, false, true), null).windows())
                .singleElement().satisfies(window -> {
                    assertThat(window.minutes()).isEqualTo(240);
                    assertThat(window.startCount()).isEqualTo(9);
                });
        Instant first = BASE.plus(Duration.ofHours(4));
        var before = observation(BASE, contract(true), grid(first, 1), List.of(new Slot(first, first.plusSeconds(7200), List.of())), List.of());
        assertThat(detector.detect(before, observation(now, false, true), null).windows()).isEmpty();
    }

    @Test void separatedStartsAreNotAContinuousSixHourLoss() {
        Instant first = BASE.plus(Duration.ofHours(4));
        var before = observation(BASE, contract(true), List.of(grid(first, 1).getFirst(), grid(first.plusSeconds(21600), 1).getFirst()),
                List.of(new Slot(first, first.plusSeconds(7200), List.of())), List.of());
        assertThat(detector.detect(before, observation(now, false, true), null).windows()).isEmpty();
    }

    @Test void shiftedGridPreservesBookingOpportunities() {
        var before = observation(BASE, true, true);
        var shifted = observation(now, contract(true), grid(before.primarySlots().getFirst().start().plusSeconds(600), 9), List.of(), List.of());
        assertThat(detector.detect(before, shifted, null).windows()).isEmpty();
        assertThat(detector.detect(before, shifted, null).reason()).isEqualTo("RETURNED_AVAILABILITY");
    }

    @Test void aRealBookingInTheMiddleSplitsTheLostWindow() {
        Instant start = BASE.plus(Duration.ofHours(6));
        var current = observation(now, contract(true), List.of(), List.of(), List.of(new BusyInterval(start, start.plusSeconds(3600), TEAM, List.of())));
        assertThat(detector.detect(observation(BASE, true, true), current, null).windows()).isEmpty();
        assertThat(detector.detect(observation(BASE, true, true), current, null).reason()).isEqualTo("BOOKING_OVERLAP");
    }

    @Test void bookingAt1130ExplainsProbeFrom1000To1200() {
        Instant ten = Instant.parse("2026-09-29T17:00:00Z");
        Slot probe = new Slot(ten, ten.plusSeconds(7200), List.of());
        var bookings = List.of(new BusyInterval(ten.plusSeconds(5400), ten.plusSeconds(12600), TEAM, List.of()));
        assertThat(ProviderAvailabilityLossDetector.explained(probe, bookings, TEAM)).isTrue();
        assertThat(ProviderAvailabilityLossDetector.explained(probe, bookings, "OTHER")).isFalse();
        assertThat(ProviderAvailabilityLossDetector.overlaps(ten, ten.plusSeconds(7200), ten.plusSeconds(7200), ten.plusSeconds(10800))).isFalse();
    }

    @Test void sharedResourcesExplainAProbeEvenForAnotherProvider() {
        Instant first = BASE.plus(Duration.ofHours(4));
        Slot slot = new Slot(first, first.plusSeconds(1800), List.of("CHAIR"));
        assertThat(ProviderAvailabilityLossDetector.explained(slot,
                List.of(new BusyInterval(first, first.plusSeconds(3600), "OTHER", List.of("CHAIR"))), TEAM)).isTrue();
    }

    @Test void returnedStartBreaksTheChain() {
        var before = observation(BASE, true, true);
        var current = observation(now, contract(true), List.of(before.primarySlots().get(4)), List.of(), List.of());
        assertThat(detector.detect(before, current, null).windows()).isEmpty();
    }

    @Test void retainedMainServiceOrMissingCorroborationPreventsAnAlert() {
        var before = observation(BASE, true, true);
        var current = observation(now, contract(true), List.of(), before.secondarySlots(), List.of());
        assertThat(detector.detect(before, current, null).windows()).isEmpty();
        var noCorroboration = observation(BASE, contract(true), before.primarySlots(), List.of(), List.of());
        assertThat(detector.detect(noCorroboration, observation(now, false, true), null).windows()).isEmpty();
    }

    @Test void metadataChangeStaleBaselineAndOutOfOrderReadsAreNotCalendarChanges() {
        var before = observation(BASE, true, true);
        assertThat(detector.detect(before, observation(now, false, false), null).reason()).isEqualTo("PROBE_CHANGED");
        assertThat(detector.detect(before, observation(BASE.plusSeconds(2760), false, true), null).reason()).isEqualTo("STALE_BASELINE");
        assertThat(detector.detect(before, before, null).reason()).isEqualTo("OUT_OF_ORDER");
    }

    @Test void noticeAppliesToTheFirstStartWithoutClippingTheEntireWindow() {
        Instant first = BASE.plus(Duration.ofHours(22));
        var before = observation(BASE, contract(true), grid(first, 9), List.of(new Slot(first, first.plusSeconds(7200), List.of())), List.of());
        assertThat(detector.detect(before, observation(now, false, true), null).windows()).hasSize(1);
        Instant later = BASE.plus(Duration.ofHours(26));
        var advance = observation(BASE, contract(true), grid(later, 9), List.of(new Slot(later, later.plusSeconds(7200), List.of())), List.of());
        assertThat(detector.detect(advance, observation(now, false, true), null).windows()).isEmpty();
    }

    @Test void localMidnightSeparatesTheDays() {
        Instant first = Instant.parse("2026-09-29T05:00:00Z"); // 22:00 Pacific
        var before = observation(BASE, contract(true), grid(first, 9), List.of(new Slot(first, first.plusSeconds(7200), List.of())), List.of());
        assertThat(detector.detect(before, observation(now, false, true), null).windows()).isEmpty();
    }

    @Test void dailyBookingLimitExplainsDisappearanceWithoutIntervalOverlap() {
        var standard = contract(true);
        var limited = new Contract(standard.locationId(), TEAM, standard.timezone(), 7200, 31536000, "SERVICE_DURATION",
                "PER_TEAM_MEMBER", 3, 24, 240, true, standard.primary(), standard.secondary());
        var before = observation(BASE, limited, observation(BASE, true, true).primarySlots(), observation(BASE, true, true).secondarySlots(), List.of());
        var empty = observation(now, limited, List.of(), List.of(), List.of());
        var current = new ProviderAvailabilityObservation(empty.capturedAt(), empty.queryStart(), empty.queryEnd(), limited,
                List.of(), List.of(), List.of(), Map.of(before.primarySlots().getFirst().start().atZone(java.time.ZoneId.of(limited.timezone())).toLocalDate(), 3));
        assertThat(detector.detect(before, current, null).reason()).isEqualTo("BOOKING_OVERLAP");
    }

    @Test void confirmationReappliesTheCutoffRatherThanCountingExpiredStarts() {
        var before = observation(BASE, true, true);
        var late = observation(BASE.plus(Duration.ofHours(2)), false, true);
        assertThat(detector.detect(before, late, now).windows()).isEmpty();
    }
}
