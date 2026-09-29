package com.salonreview.sms;

import com.salonreview.sms.ProviderAvailabilityObservation.*;
import com.salonreview.square.SquareScheduleData;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates and assembles scheduling evidence without modifying the payroll mirror. */
@Component
public class ProviderScheduleObservationReader {
    public static final Duration BOOKING_LOOKBACK = Duration.ofDays(31);
    private static final Set<String> CANCELLED = Set.of("CANCELLED_BY_CUSTOMER", "CANCELLED_BY_SELLER", "DECLINED");
    private static final Set<String> OCCUPIED = Set.of("ACCEPTED", "PENDING", "NO_SHOW");

    public Contract contract(String locationId, String teamMemberId, String timezone,
                             SquareScheduleData.Settings bookingSettings,
                             ProviderScheduleClosureAlertConfigService.Settings policy,
                             List<SquareScheduleData.Service> services,
                             Contract previous, Map<String, Long> popularity) {
        ZoneId.of(timezone);
        if (bookingSettings == null || bookingSettings.minBookingLeadTimeSeconds() == null
                || bookingSettings.minBookingLeadTimeSeconds() < 0 || bookingSettings.maxBookingLeadTimeSeconds() == null
                || bookingSettings.maxBookingLeadTimeSeconds() <= 0)
            throw new IllegalStateException("Incomplete booking settings");
        List<SquareScheduleData.Service> eligible = services.stream()
                .filter(service -> service.teamMemberIds().contains(teamMemberId)).toList();
        var primary = previous == null ? null : eligible.stream()
                .filter(service -> service.id().equals(previous.primary().serviceId()) && service.durationMinutes() <= 60)
                .findFirst().orElse(null);
        if (primary == null) primary = eligible.stream().filter(service -> service.durationMinutes() <= 60)
                .sorted(Comparator.comparingInt(SquareScheduleData.Service::durationMinutes)
                        .thenComparing(service -> -popularity.getOrDefault(service.id(), 0L))
                        .thenComparing(SquareScheduleData.Service::id)).findFirst()
                .orElseThrow(() -> new IllegalStateException("No eligible short schedule probe"));
        final String primaryId = primary.id();
        var secondary = previous == null ? null : eligible.stream()
                .filter(service -> service.id().equals(previous.secondary().serviceId()) && !service.id().equals(primaryId))
                .findFirst().orElse(null);
        if (secondary == null) secondary = eligible.stream().filter(service -> !service.id().equals(primaryId))
                .sorted(Comparator.<SquareScheduleData.Service>comparingLong(service -> -popularity.getOrDefault(service.id(), 0L))
                        .thenComparing(Comparator.comparingInt(SquareScheduleData.Service::durationMinutes).reversed())
                        .thenComparing(SquareScheduleData.Service::id)).findFirst()
                .orElseThrow(() -> new IllegalStateException("No independent schedule probe"));
        String limitType = bookingSettings.maxAppointmentsPerDayLimitType();
        Integer limit = bookingSettings.maxAppointmentsPerDayLimit();
        if (limit != null && limit > 0 && !Set.of("PER_TEAM_MEMBER", "PER_LOCATION").contains(limitType))
            throw new IllegalStateException("Unsupported booking limit");
        return new Contract(locationId, teamMemberId, timezone, bookingSettings.minBookingLeadTimeSeconds(),
                bookingSettings.maxBookingLeadTimeSeconds(), bookingSettings.alignmentTime(), limitType, limit,
                policy.noticeThresholdHours(), policy.minimumLossWindowMinutes(), policy.observationOnly(),
                probe(primary, bookingSettings.alignmentTime()), probe(secondary, bookingSettings.alignmentTime()));
    }

    private Probe probe(SquareScheduleData.Service service, String alignment) {
        int step = switch (alignment == null ? "" : alignment) {
            case "SERVICE_DURATION" -> service.durationMinutes();
            case "QUARTER_HOURLY" -> 15;
            case "HALF_HOURLY" -> 30;
            case "HOURLY" -> 60;
            default -> throw new IllegalStateException("Unsupported booking alignment");
        };
        return new Probe(service.id(), service.version(), service.durationMinutes(), step);
    }

    public List<Slot> slots(List<SquareScheduleData.Availability> data, Contract contract, Probe probe,
                            Instant start, Instant end) {
        List<Slot> result = new ArrayList<>();
        for (var availability : data) {
            if (!contract.locationId().equals(availability.locationId()) || availability.startAt() == null
                    || availability.appointmentSegments() == null || availability.appointmentSegments().size() != 1)
                throw new IllegalStateException("Unexpected availability response");
            var segment = availability.appointmentSegments().getFirst();
            if (!contract.teamMemberId().equals(segment.teamMemberId())
                    || !probe.serviceId().equals(segment.serviceVariationId())
                    || segment.durationMinutes() == null || segment.durationMinutes() != probe.durationMinutes()
                    || (segment.serviceVariationVersion() != null && segment.serviceVariationVersion() != probe.version())
                    || (segment.intermissionMinutes() != null && segment.intermissionMinutes() != 0))
                throw new IllegalStateException("Availability probe changed");
            Instant slotStart = Instant.parse(availability.startAt());
            if (slotStart.isBefore(start) || !slotStart.isBefore(end))
                throw new IllegalStateException("Availability outside query coverage");
            result.add(new Slot(slotStart, slotStart.plus(Duration.ofMinutes(segment.durationMinutes())), segment.resourceIds()));
        }
        return result.stream().distinct().sorted(Comparator.comparing(Slot::start)).toList();
    }

    public ProviderAvailabilityObservation observation(Instant capturedAt, Instant start, Instant end,
                                                       Contract contract, List<Slot> primary, List<Slot> secondary,
                                                       List<SquareScheduleData.Booking> bookings) {
        List<BusyInterval> busy = new ArrayList<>();
        Map<LocalDate, Integer> counts = new HashMap<>();
        ZoneId zone = ZoneId.of(contract.timezone());
        for (var booking : bookings) {
            if (booking.status() == null) throw new IllegalStateException("Unknown booking status");
            if (CANCELLED.contains(booking.status())) continue;
            if (!OCCUPIED.contains(booking.status()) || booking.startAt() == null
                    || !contract.locationId().equals(booking.locationId())
                    || booking.appointmentSegments() == null || booking.appointmentSegments().isEmpty())
                throw new IllegalStateException("Incomplete schedule booking");
            Instant bookingStart = Instant.parse(booking.startAt());
            LocalDate date = bookingStart.atZone(zone).toLocalDate();
            boolean belongsToProvider = booking.appointmentSegments().stream()
                    .anyMatch(segment -> contract.teamMemberId().equals(segment.teamMemberId()));
            if ("PER_LOCATION".equals(contract.dailyLimitType()) || belongsToProvider) counts.merge(date, 1, Integer::sum);
            Instant cursor = bookingStart;
            if (Boolean.TRUE.equals(booking.allDay())) {
                Instant dayStart = date.atStartOfDay(zone).toInstant();
                Instant dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant();
                for (var segment : booking.appointmentSegments()) {
                    if (segment.teamMemberId() == null || segment.teamMemberId().isBlank())
                        throw new IllegalStateException("Incomplete all-day booking");
                    busy.add(new BusyInterval(dayStart, dayEnd, segment.teamMemberId(), segment.resourceIds()));
                }
                continue;
            }
            List<SquareScheduleData.Segment> segments = booking.appointmentSegments();
            for (int i = 0; i < segments.size(); i++) {
                var segment = segments.get(i);
                if (segment.teamMemberId() == null || segment.durationMinutes() == null
                        || segment.durationMinutes() < 0 || segment.durationMinutes() > 1500
                        || (segment.intermissionMinutes() != null && segment.intermissionMinutes() < 0))
                    throw new IllegalStateException("Unsupported schedule segment timing");
                Instant segmentEnd = cursor.plus(Duration.ofMinutes(segment.durationMinutes()));
                if (i == segments.size() - 1) {
                    int transition = booking.transitionTimeMinutes() == null ? 0 : booking.transitionTimeMinutes();
                    if (transition < 0) throw new IllegalStateException("Invalid booking transition");
                    segmentEnd = segmentEnd.plus(Duration.ofMinutes(transition));
                }
                if (segmentEnd.isAfter(cursor)) busy.add(new BusyInterval(cursor, segmentEnd, segment.teamMemberId(), segment.resourceIds()));
                cursor = segmentEnd.plus(Duration.ofMinutes(segment.intermissionMinutes() == null ? 0 : segment.intermissionMinutes()));
            }
            if (Duration.between(bookingStart, cursor).compareTo(BOOKING_LOOKBACK) > 0)
                throw new IllegalStateException("Unsupported long booking");
        }
        return new ProviderAvailabilityObservation(capturedAt, start, end, contract, primary, secondary, busy, counts);
    }
}
