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

    @Column(name = "square_customer_id", nullable = false)
    private String squareCustomerId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "removed_at")
    private Instant removedAt;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
