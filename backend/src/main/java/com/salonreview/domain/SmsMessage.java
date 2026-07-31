package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One row per outbound send attempt (sent or not) and per inbound message received on the SMS
 * number — the full activity log the {@code /owner/automations} hub reads from (see V52,
 * openspec/changes/sms-automations-hub). {@code readAt} is only ever set on {@code INBOUND} rows —
 * an inbound message that doesn't match any automation still needs to visibly demand attention,
 * the same way an unread email would (design.md D9).
 */
@Entity
@Table(name = "sms_message")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class SmsMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "direction", nullable = false)
    private String direction; // "OUTBOUND" | "INBOUND"

    @Column(name = "automation_key")
    private String automationKey;

    @Column(name = "phone_number", nullable = false)
    private String phoneNumber;

    @Column(name = "template_key")
    private String templateKey;

    @Column(name = "body", nullable = false)
    private String body;

    @Column(name = "twilio_message_sid")
    private String twilioMessageSid;

    @Column(name = "status", nullable = false)
    private String status; // "SENT" | "NOT_SENT" | "RECEIVED"

    @Column(name = "reason")
    private String reason;

    @Column(name = "link_target")
    private String linkTarget; // "GOOGLE_REVIEW" | "FEEDBACK_FORM"

    /** Opaque short-link token for messages carrying a click-tracked {@code /r/{token}} link —
     * see V53, design.md D6. {@code null} for messages with no link. */
    @Column(name = "click_token")
    private String clickToken;

    @Column(name = "clicked_at")
    private Instant clickedAt;

    @Column(name = "read_at")
    private Instant readAt;

    /** Twilio's delivery-status callback for this send — "queued" | "sending" | "sent" |
     * "delivered" | "undelivered" | "failed" — distinct from {@link #status}, which only ever
     * reflects whether *our* API call to Twilio succeeded, not what happened to the message
     * afterward. {@code null} until the callback arrives (or forever, for INBOUND rows and any
     * OUTBOUND row sent before this tracking existed). */
    @Column(name = "delivery_status")
    private String deliveryStatus;

    @Column(name = "delivery_error_code")
    private String deliveryErrorCode;

    /** Human-readable translation of {@link #deliveryErrorCode} — see
     * {@code SmsMessageLogService}'s error-code lookup. */
    @Column(name = "delivery_error_message")
    private String deliveryErrorMessage;

    @Column(name = "delivery_updated_at")
    private Instant deliveryUpdatedAt;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
