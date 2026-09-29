package com.salonreview.sms;

import com.salonreview.square.SquareScheduleData;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.salonreview.sms.ProviderScheduleTestFixtures.*;
import static org.assertj.core.api.Assertions.*;

class ProviderScheduleObservationReaderTest {
    private final ProviderScheduleObservationReader reader = new ProviderScheduleObservationReader();

    @Test
    void probeIsBookableByThisProviderAndStableAcrossPopularityChanges() {
        var services = List.of(service("WRONG", 15, "OTHER"), service("SHORT", 30, TEAM),
                service("SHORTER", 20, TEAM), service("LONG", 120, TEAM), service("POPULAR", 60, TEAM));
        var selected = reader.contract("LOC", TEAM, "America/Los_Angeles", settings("SERVICE_DURATION"),
                policy(), services, contract(true), Map.of("POPULAR", 1000L));
        assertThat(selected.primary().serviceId()).isEqualTo("SHORT");
        assertThat(selected.secondary().serviceId()).isEqualTo("LONG");
        var initial = reader.contract("LOC", TEAM, "America/Los_Angeles", settings("HALF_HOURLY"),
                policy(), services, null, Map.of("POPULAR", 1000L));
        assertThat(initial.primary().serviceId()).isEqualTo("SHORTER");
        assertThat(initial.secondary().serviceId()).isEqualTo("POPULAR");
        assertThat(initial.primary().stepMinutes()).isEqualTo(30);
    }

    @Test
    void missingShortOrIndependentServiceFailsClosed() {
        assertThatThrownBy(() -> reader.contract("LOC", TEAM, "America/Los_Angeles", settings("SERVICE_DURATION"),
                policy(), List.of(service("LONG", 120, TEAM)), null, Map.of())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> reader.contract("LOC", TEAM, "America/Los_Angeles", settings("SERVICE_DURATION"),
                policy(), List.of(service("SHORT", 30, TEAM)), null, Map.of())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> reader.contract("LOC", TEAM, "America/Los_Angeles", settings("UNRECOGNIZED"),
                policy(), List.of(service("SHORT", 30, TEAM), service("LONG", 120, TEAM)), null, Map.of())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void incompleteBusinessSettingsCannotInventCutoff() {
        assertThatThrownBy(() -> reader.contract("LOC", TEAM, "America/Los_Angeles",
                new SquareScheduleData.Settings(null, 100000L, "HOURLY", null, null), policy(), List.of(), null, Map.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> reader.contract("LOC", TEAM, "Invalid/Timezone", settings("HOURLY"), policy(), List.of(), null, Map.of()))
                .isInstanceOf(java.time.DateTimeException.class);
    }

    @Test
    void validatesAvailabilityTeamLocationVersionAndDuration() {
        var c = contract(true);
        var slot = new SquareScheduleData.Availability(BASE.plusSeconds(18000).toString(), "LOC", List.of(segment(TEAM, 30, 0, "SHORT", 1L)));
        assertThat(reader.slots(List.of(slot, slot), c, c.primary(), BASE, BASE.plusSeconds(86400))).hasSize(1);
        for (var invalid : List.of(
                new SquareScheduleData.Availability(slot.startAt(), "OTHER", slot.appointmentSegments()),
                new SquareScheduleData.Availability(slot.startAt(), "LOC", List.of(segment("OTHER", 30, 0, "SHORT", 1L))),
                new SquareScheduleData.Availability(slot.startAt(), "LOC", List.of(segment(TEAM, 60, 0, "SHORT", 1L))),
                new SquareScheduleData.Availability(slot.startAt(), "LOC", List.of(segment(TEAM, 30, 0, "SHORT", 2L))),
                new SquareScheduleData.Availability(BASE.minusSeconds(1).toString(), "LOC", slot.appointmentSegments()))) {
            assertThatThrownBy(() -> reader.slots(List.of(invalid), c, c.primary(), BASE, BASE.plusSeconds(86400)))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void buildsFullSegmentIntervalsWithIntermissionTransitionAndZeroMinuteAddons() {
        var b = booking("ACCEPTED", BASE, List.of(segment("OTHER", 60, 15, "FIRST", 1L),
                segment(TEAM, 120, 0, "SECOND", 1L), segment(TEAM, 0, 0, "ADDON", 1L)), 10, false);
        var observation = read(List.of(b), contract(true));
        assertThat(observation.bookings()).hasSize(3);
        assertThat(observation.bookings().get(1).start()).isEqualTo(BASE.plusSeconds(75 * 60));
        assertThat(observation.bookings().get(1).end()).isEqualTo(BASE.plusSeconds(195 * 60));
        assertThat(observation.bookings().get(2).start()).isEqualTo(BASE.plusSeconds(195 * 60));
        assertThat(observation.bookings().get(2).end()).isEqualTo(BASE.plusSeconds(205 * 60));
        assertThat(read(List.of(booking("ACCEPTED", BASE, List.of(segment(TEAM, 60, 0, "MAIN", 1L),
                segment(TEAM, 0, 0, "ADDON", 1L)), 0, false)), contract(true)).bookings()).hasSize(1);
    }

    @Test
    void canceledBookingsAreIgnoredAndPendingBookingsStillOccupyTime() {
        var segments = List.of(segment(TEAM, 60, 0, "MAIN", 1L));
        assertThat(read(List.of(booking("CANCELLED_BY_CUSTOMER", BASE, segments, 0, false)), contract(true)).bookings()).isEmpty();
        assertThat(read(List.of(booking("PENDING", BASE, segments, 0, false)), contract(true)).bookings()).hasSize(1);
        assertThatThrownBy(() -> read(List.of(booking("NEW_UNKNOWN_STATUS", BASE, segments, 0, false)), contract(true)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> read(List.of(booking("ACCEPTED", BASE, List.of(segment(TEAM, -1, 0, "MAIN", 1L)), 0, false)), contract(true)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void allDayBookingUsesBusinessDayIncludingDstAndRequiresProviderIdentity() {
        Instant springForward = Instant.parse("2026-03-08T20:00:00Z");
        var observation = read(List.of(booking("ACCEPTED", springForward, List.of(segment(TEAM, 0, 0, "MAIN", 1L)), 0, true)), contract(true));
        assertThat(Duration.between(observation.bookings().getFirst().start(), observation.bookings().getFirst().end())).isEqualTo(Duration.ofHours(23));
        assertThatThrownBy(() -> read(List.of(booking("ACCEPTED", BASE, List.of(segment(null, 0, 0, "MAIN", 1L)), 0, true)), contract(true)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void dailyLimitCountsAreProviderScopedUnlessLocationLimitApplies() {
        var other = booking("ACCEPTED", BASE, List.of(segment("OTHER", 60, 0, "MAIN", 1L)), 0, false);
        assertThat(read(List.of(other), contract(true)).dailyBookingCounts()).isEmpty();
        var base = contract(true);
        var locationLimit = new ProviderAvailabilityObservation.Contract(base.locationId(), TEAM, base.timezone(),
                base.minBookingLeadTimeSeconds(), base.maxBookingLeadTimeSeconds(), base.alignmentTime(), "PER_LOCATION", 10,
                24, 240, true, base.primary(), base.secondary());
        assertThat(read(List.of(other), locationLimit).dailyBookingCounts().values()).containsExactly(1);
    }

    private ProviderAvailabilityObservation read(List<SquareScheduleData.Booking> bookings, ProviderAvailabilityObservation.Contract c) {
        return reader.observation(BASE, BASE, BASE.plusSeconds(86400), c, List.of(), List.of(), bookings);
    }
    private static SquareScheduleData.Service service(String id, int minutes, String team) {
        return new SquareScheduleData.Service(id, id.equals("LONG") ? 2 : 1, id, minutes, List.of(team));
    }
    private static SquareScheduleData.Settings settings(String alignment) {
        return new SquareScheduleData.Settings(7200L, 31536000L, alignment, null, null);
    }
    private static ProviderScheduleClosureAlertConfigService.Settings policy() {
        return new ProviderScheduleClosureAlertConfigService.Settings(24, 240, true, null, null);
    }
    private static SquareScheduleData.Segment segment(String team, int duration, int intermission, String id, Long version) {
        return new SquareScheduleData.Segment(team, id, version, duration, intermission, List.of());
    }
    private static SquareScheduleData.Booking booking(String status, Instant start, List<SquareScheduleData.Segment> segments, int transition, boolean allDay) {
        return new SquareScheduleData.Booking("BOOKING", status, start.toString(), "LOC", segments, transition, allDay);
    }
}
