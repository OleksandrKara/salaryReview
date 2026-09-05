package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One row per real Square booking the {@code pre_visit_nurture} automation has considered — see
 * V155, {@code PreVisitNurtureScheduler}. Doubles as idempotency marker and outcome log for both
 * steps: a warm welcome email shortly after booking, and (if the appointment is far enough out) a
 * day-before reminder — goal is fewer cancellations/no-shows through familiarity with the studio,
 * not a booking-conversion ask (the customer has already booked).
 */
@Entity
@Table(name = "pre_visit_nurture_send")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class PreVisitNurtureSend {

    public static final String STATE_SENT = "SENT";
    public static final String STATE_SKIPPED_DISABLED = "SKIPPED_DISABLED";
    public static final String STATE_SKIPPED_NO_EMAIL = "SKIPPED_NO_EMAIL";
    public static final String STATE_SKIPPED_NOT_CONFIGURED = "SKIPPED_NOT_CONFIGURED";
    public static final String STATE_SKIPPED_NO_TEMPLATE = "SKIPPED_NO_TEMPLATE";
    public static final String STATE_SEND_FAILED = "SEND_FAILED";
    /** Reminder step only — the booking was cancelled sometime between the welcome email and the
     * day-before reminder check; a reminder for a visit that's no longer happening would read as
     * broken, not caring. */
    public static final String STATE_SKIPPED_CANCELLED = "SKIPPED_CANCELLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false)
    private Long businessId;

    @Column(name = "square_booking_id", nullable = false)
    private String squareBookingId;

    @Column(name = "square_customer_id")
    private String squareCustomerId;

    @Column(name = "appointment_start_at", nullable = false)
    private Instant appointmentStartAt;

    /** Step 1 — {@code null} until considered, one of the {@code STATE_*} constants after. */
    @Column(name = "welcome_state")
    private String welcomeState;

    /** Step 2 (~1 day before) — {@code null} until considered (including "never eligible because
     * the appointment was always too soon after booking to have a day-before at all," which simply
     * never gets picked up by the reminder poll's own window rather than being marked any
     * particular state). */
    @Column(name = "reminder_state")
    private String reminderState;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
