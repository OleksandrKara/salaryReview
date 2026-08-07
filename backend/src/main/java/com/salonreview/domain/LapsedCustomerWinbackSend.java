package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One row per customer, ever, for the {@code lapsed_customer_winback} automation — see V68,
 * openspec/changes/lapsed-customer-winback-automation design.md D4. Doubles as both the
 * idempotency marker and the outcome log, same shape as {@link LeadFollowUpSend}: this automation
 * is genuinely one-way, no reply-wait phase needed.
 */
@Entity
@Table(name = "lapsed_customer_winback_send")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class LapsedCustomerWinbackSend {

    public static final String STATE_SENT = "SENT";
    public static final String STATE_SKIPPED_BOOKED = "SKIPPED_BOOKED";
    public static final String STATE_SKIPPED_DISABLED = "SKIPPED_DISABLED";
    public static final String STATE_SKIPPED_NEGATIVE_FEEDBACK = "SKIPPED_NEGATIVE_FEEDBACK";
    public static final String STATE_SKIPPED_UNRESOLVED = "SKIPPED_UNRESOLVED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "square_customer_id", nullable = false)
    private String squareCustomerId;

    @Column(name = "phone_number")
    private String phoneNumber;

    @Column(name = "customer_name")
    private String customerName;

    @Column(name = "visit_date", nullable = false)
    private LocalDate visitDate;

    /** Computed once at send time (see design.md D10) — the deadline for the $5 coupon this
     * customer's link carries, end of the day the SMS went out, not the visit day. Null for every
     * {@code SKIPPED_*} state (no send, no coupon link generated). */
    @Column(name = "promo_expires_at")
    private Instant promoExpiresAt;

    @Column(name = "state", nullable = false)
    private String state;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
