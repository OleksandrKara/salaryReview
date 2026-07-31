package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One row per qualifying Square payment for the {@code same_day_rebooking_discount} automation —
 * see V55, openspec/changes/same-day-rebooking-discount design.md D1. Durable delayed-send state
 * (like {@link SmsReplyFlow}) but one-way — no reply-wait phase, since the "reply" is a website
 * visit + booking, not an SMS reply back.
 */
@Entity
@Table(name = "same_day_rebooking_send")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class SameDayRebookingSend {

    public static final String STATE_AWAITING_SEND = "AWAITING_SEND";
    public static final String STATE_SENT = "SENT";
    public static final String STATE_SKIPPED_BOOKED = "SKIPPED_BOOKED";
    public static final String STATE_SKIPPED_NO_CONSENT = "SKIPPED_NO_CONSENT";
    public static final String STATE_SKIPPED_EXPIRED = "SKIPPED_EXPIRED";
    public static final String STATE_SKIPPED_DISABLED = "SKIPPED_DISABLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "phone_number", nullable = false)
    private String phoneNumber;

    @Column(name = "customer_name")
    private String customerName;

    @Column(name = "square_customer_id", nullable = false)
    private String squareCustomerId;

    @Column(name = "square_payment_id", nullable = false)
    private String squarePaymentId;

    @Column(name = "send_due_at", nullable = false)
    private Instant sendDueAt;

    @Column(name = "promo_expires_at", nullable = false)
    private Instant promoExpiresAt;

    @Column(name = "state", nullable = false)
    private String state;

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
