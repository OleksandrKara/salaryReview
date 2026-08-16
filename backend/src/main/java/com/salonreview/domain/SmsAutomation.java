package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One row per (business, owner-toggleable SMS automation) (see V52/V104). {@code enabled}
 * defaults to {@code false} at the schema level — any newly-added automation is never live until
 * an OWNER explicitly turns it on from {@code /owner/automations} (see
 * openspec/changes/sms-automations-hub/design.md D8). Keyed by business + automation key so each
 * business can independently enable/disable an automation.
 */
@Entity
@Table(name = "sms_automation")
@IdClass(SmsAutomationId.class)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class SmsAutomation {

    @Id
    @Column(name = "business_id")
    private Long businessId;

    @Id
    @Column(name = "automation_key")
    private String automationKey;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private String updatedBy;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
