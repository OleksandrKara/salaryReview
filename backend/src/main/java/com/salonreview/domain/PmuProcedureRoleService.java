package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Optional, owner-configurable mapping from a Square catalog variation_id to the role it plays in
 * a customer's PMU procedure lifecycle ({@link Role}) — see V123. Never hardcoded in Java: a
 * business's real qualifying services (e.g. which of several touch-up variations, split by
 * provider and time window, count as "the touch-up") are data the owner can add to over time
 * without a deploy. No rows for a (business, role) pair means that role has nothing configured
 * yet — any automation reading it should treat that as "not eligible for anyone," not fall back to
 * a default service.
 */
@Entity
@Table(name = "pmu_procedure_role_service",
       uniqueConstraints = @UniqueConstraint(columnNames = {"business_id", "role", "square_variation_id"}))
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class PmuProcedureRoleService {

    public enum Role {
        INITIAL_PROCEDURE, TOUCH_UP, COLOR_BOOSTER, CONSULTATION
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false)
    private Long businessId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private Role role;

    /** Square catalog item variation id — the same id an {@code AttributedService}/order line
     * carries, not the parent item id (a "Touch-Up by Anastasiia" item has one variation per time
     * window; the variation is what's actually sold). */
    @Column(name = "square_variation_id", nullable = false)
    private String squareVariationId;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
