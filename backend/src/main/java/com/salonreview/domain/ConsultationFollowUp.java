package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** One PMU consultation the consultation_follow_up sequence has considered (V162,
 * {@code ConsultationFollowUpScheduler}). Each step's state is {@code null} until it was due, then
 * one of the {@code STATE_*} values; {@link #stopReason} ends the whole sequence. */
@Entity
@Table(name = "consultation_follow_up")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class ConsultationFollowUp {

    public static final String STATE_SENT = "SENT";
    public static final String STATE_SKIPPED_NO_EMAIL = "SKIPPED_NO_EMAIL";
    public static final String STATE_SKIPPED_LATE = "SKIPPED_LATE";
    public static final String STATE_SKIPPED_NO_TEMPLATE = "SKIPPED_NO_TEMPLATE";
    public static final String STATE_SKIPPED = "SKIPPED";
    public static final String STATE_SEND_FAILED = "SEND_FAILED";

    public static final String STOP_BOOKED = "BOOKED";
    public static final String STOP_REPLIED = "REPLIED";
    public static final String STOP_STAFF = "STAFF";
    public static final String STOP_NOT_ELIGIBLE = "NOT_ELIGIBLE";
    public static final String STOP_CANCELLED = "CANCELLED";
    /** A one-off re-engagement offer row (ConsultationReengageOneOffService), never part of the
     * regular sequence. */
    public static final String STOP_REENGAGE = "REENGAGE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false)
    private Long businessId;

    @Column(name = "square_booking_id", nullable = false)
    private String squareBookingId;

    @Column(name = "square_customer_id", nullable = false)
    private String squareCustomerId;

    @Column(name = "team_member_id")
    private String teamMemberId;

    @Column(name = "artist_name")
    private String artistName;

    @Column(name = "visit_kind")
    private String visitKind;

    @Column(name = "consultation_start_at", nullable = false)
    private Instant consultationStartAt;

    @Column(name = "customer_name")
    private String customerName;

    @Column(name = "phone_number")
    private String phoneNumber;

    @Column(name = "staff_alert_sent_at")
    private Instant staffAlertSentAt;

    @Column(name = "thanks_sms_state")
    private String thanksSmsState;

    @Column(name = "info_email_state")
    private String infoEmailState;

    @Column(name = "checkin_sms_state")
    private String checkinSmsState;

    @Column(name = "offer_state")
    private String offerState;

    @Column(name = "offer_expires_at")
    private Instant offerExpiresAt;

    @Column(name = "offer_extended_at")
    private Instant offerExtendedAt;

    @Column(name = "stop_reason")
    private String stopReason;

    @Column(name = "stopped_at")
    private Instant stoppedAt;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
