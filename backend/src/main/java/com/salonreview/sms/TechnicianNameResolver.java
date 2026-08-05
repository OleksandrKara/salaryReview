package com.salonreview.sms;

import com.salonreview.domain.Provider;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.square.SquareBookingFilters;
import com.salonreview.square.SquareClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Resolves the technician's display name for a customer's most recent past appointment, for the
 * handful of SMS templates that name-drop "your technician" for warmth (see the SMS lifecycle
 * audit). Best-effort only: every caller falls back to technician-less copy when this returns
 * empty, so a resolution miss never blocks a send — the same fallback-preserving pattern used
 * elsewhere in this codebase (e.g. Ads Report's booking-creation-date bucketing).
 */
@Component
public class TechnicianNameResolver {

    private static final Logger log = LoggerFactory.getLogger(TechnicianNameResolver.class);

    /** Wide enough to comfortably cover "the visit that just happened" even if the SMS send is a
     * few hours delayed (same-day-rebooking) without pulling in unrelated older history. */
    private static final Duration LOOKBACK = Duration.ofDays(2);

    private final SquareClient square;
    private final ProviderRepository providers;

    public TechnicianNameResolver(SquareClient square, ProviderRepository providers) {
        this.square = square;
        this.providers = providers;
    }

    /** {@code empty} if there's no resolvable past booking, no team member on its segments, or no
     * {@link Provider} record mapped to that Square team-member id yet — any of which just means
     * the caller should use its technician-less copy instead. */
    public Optional<String> resolveForCustomer(String customerId, Instant asOf) {
        if (customerId == null || customerId.isBlank()) {
            return Optional.empty();
        }
        try {
            return square.bookingsForCustomer(customerId, asOf.minus(LOOKBACK)).stream()
                    .filter(SquareBookingFilters::didHappen)
                    .filter(b -> alreadyHappened(b, asOf))
                    .findFirst() // list is already sorted most-recent-first
                    .flatMap(TechnicianNameResolver::firstTeamMemberId)
                    .flatMap(providers::findBySquareTeamMemberId)
                    .map(Provider::getDisplayName);
        } catch (RuntimeException e) {
            log.warn("Technician-name resolution failed for customer {} (falling back to technician-less copy): {}",
                    customerId, e.getMessage());
            return Optional.empty();
        }
    }

    private static boolean alreadyHappened(SquareClient.Booking b, Instant asOf) {
        if (b.startAt() == null) return false;
        try {
            return !Instant.parse(b.startAt()).isAfter(asOf);
        } catch (Exception e) {
            return false;
        }
    }

    private static Optional<String> firstTeamMemberId(SquareClient.Booking b) {
        if (b.appointmentSegments() == null || b.appointmentSegments().isEmpty()) {
            return Optional.empty();
        }
        return b.appointmentSegments().stream()
                .map(SquareClient.AppointmentSegment::teamMemberId)
                .filter(id -> id != null && !id.isBlank())
                .findFirst();
    }
}
