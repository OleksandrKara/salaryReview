package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Optional, owner-configurable mapping from a Square catalog variation_id to the role it plays in
 * a customer's service lifecycle for a given business (e.g. initial visit, follow-up/touch-up,
 * periodic refresh, consultation) — see V123/V124. Never hardcoded in Java, and {@code role} is a
 * plain string, not a fixed enum: which stages exist, and which of possibly several Square
 * variations qualify for each, is business- and vertical-specific data (a PMU studio's real
 * catalog has 9 touch-up variations split by provider/time-window; a lash or facial business would
 * define its own, differently-named stages) — the same "registry of plain string keys, not a fixed
 * enum" convention {@link com.salonreview.sms.SmsAutomationRegistry} already uses for automation
 * keys, so a new business or a new lifecycle stage never needs a Java change. No rows for a
 * (business, role) pair means that role has nothing configured yet — any automation reading it
 * should treat that as "not eligible for anyone," not fall back to a default service.
 */
@Entity
@Table(name = "service_lifecycle_role",
       uniqueConstraints = @UniqueConstraint(columnNames = {"business_id", "role", "square_variation_id"}))
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class ServiceLifecycleRole {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false)
    private Long businessId;

    /** A business-defined lifecycle stage key — e.g. {@code "TOUCH_UP"}, {@code "COLOR_BOOSTER"},
     * {@code "INITIAL_PROCEDURE"}, {@code "CONSULTATION"} for PMU today. See
     * {@link ServiceLifecycleRoles} for the small set of well-known constants already in use,
     * added to as new automations need new stages — never a fixed enum. */
    @Column(name = "role", nullable = false)
    private String role;

    /** Square catalog item variation id — the same id an {@code AttributedService}/order line
     * carries, not the parent item id (e.g. a "Touch-Up by Anastasiia" item has one variation per
     * time window; the variation is what's actually sold). */
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
