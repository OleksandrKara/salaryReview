package com.salonreview.sms;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Immutable, comparable schedule evidence; contains no customer names, notes or credentials. */
public record ProviderAvailabilityObservation(Instant capturedAt, Instant queryStart, Instant queryEnd,
                                             Contract contract, List<Slot> primarySlots,
                                             List<Slot> secondarySlots, List<BusyInterval> bookings,
                                             Map<LocalDate, Integer> dailyBookingCounts) {
    public ProviderAvailabilityObservation {
        primarySlots = List.copyOf(primarySlots);
        secondarySlots = List.copyOf(secondarySlots);
        bookings = List.copyOf(bookings);
        dailyBookingCounts = Map.copyOf(dailyBookingCounts);
    }

    public record Probe(String serviceId, long version, int durationMinutes, int stepMinutes) {}

    public record Contract(String locationId, String teamMemberId, String timezone,
                           long minBookingLeadTimeSeconds, long maxBookingLeadTimeSeconds,
                           String alignmentTime, String dailyLimitType, Integer dailyLimit,
                           int noticeThresholdHours, int minimumLossWindowMinutes,
                           boolean observationOnly, Probe primary, Probe secondary) {}

    public record Slot(Instant start, Instant end, List<String> resourceIds) {
        public Slot { resourceIds = resourceIds == null ? List.of() : List.copyOf(resourceIds); }
    }

    public record BusyInterval(Instant start, Instant end, String teamMemberId, List<String> resourceIds) {
        public BusyInterval { resourceIds = resourceIds == null ? List.of() : List.copyOf(resourceIds); }
    }
}
