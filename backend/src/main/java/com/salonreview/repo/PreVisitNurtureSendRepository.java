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
}
