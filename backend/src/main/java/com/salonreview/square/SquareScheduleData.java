package com.salonreview.square;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

/** Schedule-only reads. Deliberately separate from the payroll/mirror DTOs. */
public final class SquareScheduleData {
    private SquareScheduleData() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Error(String code) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TokenStatus(List<String> scopes, List<Error> errors) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Settings(Long minBookingLeadTimeSeconds, Long maxBookingLeadTimeSeconds,
                           String alignmentTime, String maxAppointmentsPerDayLimitType,
                           Integer maxAppointmentsPerDayLimit) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record BusinessProfile(Boolean bookingEnabled, Settings businessAppointmentSettings) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record BusinessProfileResponse(BusinessProfile businessBookingProfile, List<Error> errors) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TeamProfile(String teamMemberId, String displayName, Boolean isBookable) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TeamProfilesResponse(List<TeamProfile> teamMemberBookingProfiles, String cursor,
                                       List<Error> errors) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Segment(String teamMemberId, String serviceVariationId, Long serviceVariationVersion,
                          Integer durationMinutes, Integer intermissionMinutes, List<String> resourceIds) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Availability(String startAt, String locationId, List<Segment> appointmentSegments) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AvailabilityResponse(List<Availability> availabilities, List<Error> errors) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Booking(String id, String status, String startAt, String locationId,
                          List<Segment> appointmentSegments, Integer transitionTimeMinutes, Boolean allDay) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record BookingsResponse(List<Booking> bookings, String cursor, List<Error> errors) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record VariationData(String name, Long serviceDuration, Boolean availableForBooking,
                                List<String> teamMemberIds) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ItemData(String name, Boolean isArchived, List<CatalogObject> variations) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CatalogObject(String id, Long version, Boolean isDeleted, ItemData itemData,
                                VariationData itemVariationData, Boolean presentAtAllLocations,
                                List<String> presentAtLocationIds, List<String> absentAtLocationIds) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CatalogResponse(List<CatalogObject> objects, String cursor, List<Error> errors) {}

    public record Service(String id, long version, String name, int durationMinutes, List<String> teamMemberIds) {}

    public static void requireSuccess(Object response, List<Error> errors) {
        if (response == null || (errors != null && !errors.isEmpty())) {
            throw new IllegalStateException("Invalid Square schedule response");
        }
    }
}
