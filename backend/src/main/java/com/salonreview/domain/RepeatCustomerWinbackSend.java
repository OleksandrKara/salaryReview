package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One row per send attempt for the {@code repeat_customer_winback} automation — see V72. Unlike
 * {@link LapsedCustomerWinbackSend} (one-shot per customer, ever), this automation is recurring: a
 * customer can lapse, come back, and lapse again, so the same {@code square_customer_id} can
 * legitimately appear more than once here. The 60-day cooldown between sends is enforced by the
 * eligibility query itself (only a {@code SENT} row within the last 60 days excludes a customer),
 * not by a unique constraint on this table.
 */
@Entity
@Table(name = "repeat_customer_winback_send")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class RepeatCustomerWinbackSend {

    public static final String STATE_SENT = "SENT";
    public static final String STATE_SKIPPED_BOOKED = "SKIPPED_BOOKED";
    public static final String STATE_SKIPPED_DISABLED = "SKIPPED_DISABLED";
    public static final String STATE_SKIPPED_NEGATIVE_FEEDBACK = "SKIPPED_NEGATIVE_FEEDBACK";
    public static final String STATE_SKIPPED_UNRESOLVED = "SKIPPED_UNRESOLVED";
    public static final String STATE_SKIPPED_BLOCKED = "SKIPPED_BLOCKED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false)
    private Long businessId;

    @Column(name = "square_customer_id", nullable = false)
    private String squareCustomerId;

    @Column(name = "phone_number")
    private String phoneNumber;

    @Column(name = "last_visit_date", nullable = false)
    private LocalDate lastVisitDate;

    @Column(name = "days_since_last_visit", nullable = false)
    private Integer daysSinceLastVisit;

    @Column(name = "total_visit_count", nullable = false)
    private Integer totalVisitCount;

    /** {@code provider_visit.provider_name} of this customer's most recent completed visit. */
    @Column(name = "last_provider")
    private String lastProvider;

    /** {@code provider_visit.provider_name} of the visit immediately before that one. */
    @Column(name = "previous_provider")
    private String previousProvider;

    @Column(name = "provider_changed")
    private Boolean providerChanged;

    /** Whether that most recent visit's own {@code rebooked_same_day} flag was set — a purely
     * descriptive field for later analysis, not itself a trigger condition. */
    @Column(name = "rebooked_same_day")
    private Boolean rebookedSameDay;

    /** "default" or "previous_provider" — see {@code RepeatCustomerWinbackScheduler}. Null for
     * every {@code SKIPPED_*} state (no body was ever built). */
    @Column(name = "message_variant")
    private String messageVariant;

    /** End of the Pacific-time day this promo was sent on — the same instant baked into this
     * send's {@code sms_message.link_target} as {@code WINBACK:<epochSeconds>}, kept here too
     * purely for audit/reporting (see V78). Null for every {@code SKIPPED_*} state. */
    @Column(name = "promo_expires_at")
    private Instant promoExpiresAt;

    @Column(name = "state", nullable = false)
    private String state;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
