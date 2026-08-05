package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One row per in-flight checkout-review conversation (see V52,
 * openspec/changes/sms-automations-hub/design.md D3). A durable, DB-backed delayed-send job —
 * not an in-memory timer — so a pending 2-minute delay survives a backend restart/redeploy.
 *
 * <p>State machine: {@code AWAITING_SEND} → {@code AWAITING_REPLY} → {@code COMPLETED} |
 * {@code EXPIRED}.
 */
@Entity
@Table(name = "sms_reply_flow")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class SmsReplyFlow {

    public static final String STATE_AWAITING_SEND = "AWAITING_SEND";
    public static final String STATE_AWAITING_REPLY = "AWAITING_REPLY";
    public static final String STATE_COMPLETED = "COMPLETED";
    public static final String STATE_EXPIRED = "EXPIRED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "automation_key", nullable = false)
    private String automationKey;

    @Column(name = "phone_number", nullable = false)
    private String phoneNumber;

    @Column(name = "customer_name")
    private String customerName;

    @Column(name = "state", nullable = false)
    private String state;

    @Column(name = "square_payment_id")
    private String squarePaymentId;

    /** Nullable — rows created before V67 have none, and {@link com.salonreview.sms.SmsReplyFlowScheduler}
     * falls back to technician-less copy in that case (see {@code TechnicianNameResolver}). */
    @Column(name = "square_customer_id")
    private String squareCustomerId;

    @Column(name = "send_due_at", nullable = false)
    private Instant sendDueAt;

    @Column(name = "reply_expires_at")
    private Instant replyExpiresAt;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
