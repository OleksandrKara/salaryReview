package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Tracks which Square customers are currently enrolled in the live "same-day rebooking" Square
 * customer group backing the auto-applying discount — see V55,
 * openspec/changes/same-day-rebooking-discount design.md D7. {@code removedAt} is set once the
 * group-expiry sweep has removed that customer from the Square-side group; a row with
 * {@code removedAt == null} and {@code expiresAt} in the past is due for removal.
 */
@Entity
@Table(name = "same_day_rebooking_group_membership")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class SameDayRebookingGroupMembership {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false)
    private Long businessId;

    @Column(name = "square_customer_id", nullable = false)
    private String squareCustomerId;

    /** Which Square customer group this row actually enrolled into — see V71. Null for rows
     * written before this column existed, all of which were the original $10
     * same_day_rebooking_discount group; {@link com.salonreview.sms.SameDayRebookingGroupExpiryScheduler}
     * falls back to that group id when this is null so old rows keep working unchanged. */
    @Column(name = "group_id")
    private String groupId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "removed_at")
    private Instant removedAt;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
