package com.salonreview.repo;

import com.salonreview.domain.PreVisitNurtureSend;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface PreVisitNurtureSendRepository extends JpaRepository<PreVisitNurtureSend, Long> {

    /** Idempotency check for step 1 — one row per real booking, ever. See
     * {@code PreVisitNurtureScheduler}. */
    boolean existsByBusinessIdAndSquareBookingId(Long businessId, String squareBookingId);

    /** Step 2 (day-before reminder) candidates: welcomed already, not yet considered for the
     * reminder, and the appointment itself falls inside the reminder window — a booking made only
     * hours before its own start time simply never lands in this window at all (no "too soon"
     * state needed; it's structurally excluded, not skipped). */
    List<PreVisitNurtureSend> findByBusinessIdAndWelcomeStateAndReminderStateIsNullAndAppointmentStartAtBetween(
            Long businessId, String welcomeState, Instant windowStart, Instant windowEnd);

    /** "Meet your artist" candidates: welcomed, not yet considered for this step, booked at least
     * {@code bookedBefore} ago and the visit still at least until {@code startsAfter} away. The
     * scheduler applies the minimum total wait itself. */
    List<PreVisitNurtureSend> findByBusinessIdAndWelcomeStateAndMeetArtistStateIsNullAndCreatedAtBeforeAndAppointmentStartAtAfter(
            Long businessId, String welcomeState, Instant bookedBefore, Instant startsAfter);

    /** "Getting ready" candidates: welcomed, not yet considered for this step, visit inside the
     * window. */
    List<PreVisitNurtureSend> findByBusinessIdAndWelcomeStateAndPrepStateIsNullAndAppointmentStartAtBetween(
            Long businessId, String welcomeState, Instant windowStart, Instant windowEnd);

    /** Welcomed bookings whose visit is still ahead and not yet reminded about: the set whose
     * stored start time is re-read from the booking mirror each pass, so a rescheduled visit gets
     * its later emails at the new time instead of the old one. */
    List<PreVisitNurtureSend> findByBusinessIdAndWelcomeStateAndReminderStateIsNullAndAppointmentStartAtAfter(
            Long businessId, String welcomeState, Instant startsAfter);
}
