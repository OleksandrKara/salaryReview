package com.salonreview.sms;

import com.salonreview.sms.ProviderAvailabilityObservation.BusyInterval;
import com.salonreview.sms.ProviderAvailabilityObservation.Slot;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Detects loss of online start opportunities, never an actor or hours of a closed shift. */
@Component
public class ProviderAvailabilityLossDetector {
    public static final Duration CUTOFF_MARGIN = Duration.ofMinutes(2);
    public static final Duration MAX_BASELINE_AGE = Duration.ofMinutes(45);
    public static final Duration CONFIRMATION_DELAY = Duration.ofMinutes(20);

    public record Window(LocalDate date, Instant firstStart, Instant lastStart, int startCount) {
        public long minutes() { return Duration.between(firstStart, lastStart).toMinutes(); }
    }

    public record Result(String reason, List<Window> windows) {
        static Result none(String reason) { return new Result(reason, List.of()); }
    }

    /** firstDetectedAt=null for initial detection; set for immutable candidate confirmation. */
    public Result detect(ProviderAvailabilityObservation before, ProviderAvailabilityObservation current,
                         Instant firstDetectedAt) {
        if (!before.contract().equals(current.contract())) return Result.none("PROBE_CHANGED");
        if (!current.capturedAt().isAfter(before.capturedAt())) return Result.none("OUT_OF_ORDER");
        if (firstDetectedAt == null && Duration.between(before.capturedAt(), current.capturedAt())
                .compareTo(MAX_BASELINE_AGE) > 0) return Result.none("STALE_BASELINE");

        var contract = current.contract();
        ZoneId zone = ZoneId.of(contract.timezone());
        Instant detectedAt = firstDetectedAt == null ? current.capturedAt() : firstDetectedAt;
        Instant minimum = current.capturedAt().plusSeconds(contract.minBookingLeadTimeSeconds()).plus(CUTOFF_MARGIN);
        if (before.queryStart().isAfter(minimum)) minimum = before.queryStart();
        if (current.queryStart().isAfter(minimum)) minimum = current.queryStart();
        Instant maximum = before.queryEnd().isBefore(current.queryEnd()) ? before.queryEnd() : current.queryEnd();
        Instant maxLead = current.capturedAt().plusSeconds(contract.maxBookingLeadTimeSeconds());
        if (maxLead.isBefore(maximum)) maximum = maxLead;
        final Instant lower = minimum;
        final Instant upper = maximum;
        List<Slot> comparable = before.primarySlots().stream()
                .filter(slot -> slot.start().isAfter(lower) && slot.start().isBefore(upper))
                .sorted(Comparator.comparing(Slot::start)).toList();
        if (comparable.isEmpty()) return Result.none("NATURAL_CUTOFF");

        Map<LocalDate, List<Slot>> days = new TreeMap<>();
        comparable.forEach(slot -> days.computeIfAbsent(slot.start().atZone(zone).toLocalDate(), ignored -> new ArrayList<>()).add(slot));
        List<Window> windows = new ArrayList<>();
        boolean bookingExplanation = false;
        boolean returned = false;
        for (var day : days.entrySet()) {
            if (contract.dailyLimit() != null && contract.dailyLimit() > 0
                    && current.dailyBookingCounts().getOrDefault(day.getKey(), 0) >= contract.dailyLimit()) {
                bookingExplanation = true;
                continue;
            }
            List<Slot> chain = new ArrayList<>();
            Window best = null;
            for (Slot slot : day.getValue()) {
                boolean occupied = explained(slot, current.bookings(), contract.teamMemberId());
                boolean available = current.primarySlots().stream().anyMatch(now -> now.start().equals(slot.start()));
                if (occupied || available) {
                    bookingExplanation |= occupied;
                    returned |= available;
                    best = longer(best, qualifying(chain, before, current, detectedAt, day.getKey()));
                    chain.clear();
                    continue;
                }
                if (!chain.isEmpty()) {
                    Instant prior = chain.getLast().start();
                    boolean gap = Duration.between(prior, slot.start()).compareTo(Duration.ofMinutes(contract.primary().stepMinutes())) > 0;
                    boolean shiftedStart = current.primarySlots().stream()
                            .anyMatch(now -> now.start().isAfter(prior) && now.start().isBefore(slot.start()));
                    if (gap || shiftedStart) {
                        returned |= shiftedStart;
                        best = longer(best, qualifying(chain, before, current, detectedAt, day.getKey()));
                        chain.clear();
                    }
                }
                chain.add(slot);
            }
            best = longer(best, qualifying(chain, before, current, detectedAt, day.getKey()));
            if (best != null) windows.add(best);
        }
        if (!windows.isEmpty()) return new Result("LARGE_LOSS", List.copyOf(windows));
        return Result.none(bookingExplanation ? "BOOKING_OVERLAP" : returned ? "RETURNED_AVAILABILITY" : "SHORT_LOSS");
    }

    private Window qualifying(List<Slot> chain, ProviderAvailabilityObservation before, ProviderAvailabilityObservation current,
                              Instant detectedAt, LocalDate date) {
        if (chain.size() < 2) return null;
        Instant first = chain.getFirst().start();
        Instant last = chain.getLast().start();
        if (!first.isBefore(detectedAt.plus(Duration.ofHours(current.contract().noticeThresholdHours())))
                || Duration.between(first, last).toMinutes() < current.contract().minimumLossWindowMinutes()) return null;
        // A service-specific disappearance is insufficient if other observed booking options remain.
        if (current.secondarySlots().stream().anyMatch(slot -> !slot.start().isAfter(last) && slot.end().isAfter(first))) return null;
        if (before.secondarySlots().stream().noneMatch(slot -> !slot.start().isBefore(first) && !slot.start().isAfter(last)
                && !explained(slot, current.bookings(), current.contract().teamMemberId()))) return null;
        return new Window(date, first, last, chain.size());
    }

    private static Window longer(Window left, Window right) {
        if (left == null) return right;
        if (right == null) return left;
        return right.minutes() > left.minutes() ? right : left;
    }

    public static boolean explained(Slot slot, List<BusyInterval> bookings, String teamMemberId) {
        return bookings.stream().anyMatch(booking -> overlaps(slot.start(), slot.end(), booking.start(), booking.end())
                && (teamMemberId.equals(booking.teamMemberId())
                || slot.resourceIds().stream().anyMatch(booking.resourceIds()::contains)));
    }

    public static boolean overlaps(Instant leftStart, Instant leftEnd, Instant rightStart, Instant rightEnd) {
        return leftStart.isBefore(rightEnd) && rightStart.isBefore(leftEnd);
    }
}
