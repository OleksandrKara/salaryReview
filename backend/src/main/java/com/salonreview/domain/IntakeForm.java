package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** One first-visit intake form per customer; see V224 and {@code com.salonreview.intake}. */
@Entity
@Table(name = "intake_form")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class IntakeForm {

    public static final String PENDING = "PENDING";
    public static final String SENT = "SENT";
    public static final String SIGNED = "SIGNED";

    public static final String VIA_CLIENT_FORM = "CLIENT_FORM";
    public static final String VIA_STAFF_PAPER = "STAFF_PAPER";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false)
    private Long businessId;

    @Column(name = "square_customer_id", nullable = false)
    private String squareCustomerId;

    @Column(name = "square_booking_id", nullable = false)
    private String squareBookingId;

    @Column(name = "provider_id")
    private Long providerId;

    @Column(name = "phone_number", nullable = false)
    private String phoneNumber;

    @Column(name = "first_name")
    private String firstName;

    @Column(nullable = false, unique = true)
    private String token;

    @Column(name = "appointment_start_at", nullable = false)
    private Instant appointmentStartAt;

    @Column(nullable = false)
    private String status;

    @Column(name = "initial_sms_state")
    private String initialSmsState;

    @Column(name = "reminder_sms_state")
    private String reminderSmsState;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "reminder_sent_at")
    private Instant reminderSentAt;

    @Column(name = "last_manual_resend_at")
    private Instant lastManualResendAt;

    @Column(name = "opened_at")
    private Instant openedAt;

    @Column(name = "signed_at")
    private Instant signedAt;

    @Column(name = "signed_via")
    private String signedVia;

    @Column(name = "needs_attention", nullable = false)
    private boolean needsAttention;

    /** CRITICAL or ATTENTION when a flagged answer was given (see IntakeFlagRules); null otherwise. */
    @Column(name = "attention_level")
    private String attentionLevel;

    /** Managers' Telegram alert: PENDING, SENT, FAILED or SKIPPED (an old form, no alert). */
    @Column(name = "critical_alert_state")
    private String criticalAlertState;

    @Column(name = "critical_alert_attempts", nullable = false)
    private int criticalAlertAttempts;

    @Column(name = "critical_alerted_at")
    private Instant criticalAlertedAt;

    @Column(name = "consent_text")
    private String consentText;

    @Column(name = "consent_version")
    private String consentVersion;

    @Column(name = "signature_name")
    private String signatureName;

    @Column(name = "answers_encrypted")
    private String answersEncrypted;

    @Column(name = "square_synced_status")
    private String squareSyncedStatus;

    @Column(name = "square_sync_attempts", nullable = false)
    private int squareSyncAttempts;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    public boolean isSigned() {
        return SIGNED.equals(status);
    }
}
