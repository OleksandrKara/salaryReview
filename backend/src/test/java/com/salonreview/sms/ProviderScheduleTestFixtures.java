package com.salonreview.sms;

import com.salonreview.sms.ProviderAvailabilityObservation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

public final class ProviderScheduleTestFixtures {
    private ProviderScheduleTestFixtures() {}
    public static final Instant BASE = Instant.parse("2026-09-28T20:00:00Z");
    public static final String TEAM = "TEAM";
    public static Contract contract(boolean shadow) {
        return new Contract("LOC", TEAM, "America/Los_Angeles", 7200, 31536000, "SERVICE_DURATION", null, null,
                24, 240, shadow, new Probe("SHORT", 1, 30, 30), new Probe("LONG", 2, 120, 120));
    }
    public static List<Slot> grid(Instant first, int count) {
        return IntStream.range(0, count).mapToObj(i -> {
            Instant start = first.plus(Duration.ofMinutes(i * 30L));
            return new Slot(start, start.plus(Duration.ofMinutes(30)), List.of());
        }).toList();
    }
    public static ProviderAvailabilityObservation observation(Instant at, boolean available, boolean shadow) {
        Instant first = BASE.plus(Duration.ofHours(4));
        return observation(at, contract(shadow), available ? grid(first, 9) : List.of(),
                available ? List.of(new Slot(first, first.plus(Duration.ofHours(2)), List.of())) : List.of(), List.of());
    }
    public static ProviderAvailabilityObservation observation(Instant at, Contract contract, List<Slot> primary,
                                                             List<Slot> secondary, List<BusyInterval> bookings) {
        return new ProviderAvailabilityObservation(at, at, at.plus(Duration.ofHours(48)), contract,
                primary, secondary, bookings, Map.of());
    }
}
