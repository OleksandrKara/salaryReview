package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One row per owner-toggleable SMS automation (see V52). {@code enabled} defaults to
 * {@code false} at the schema level — any newly-added automation is never live until an OWNER
 * explicitly turns it on from {@code /owner/automations} (see
 * openspec/changes/sms-automations-hub/design.md D8).
 */
@Entity
@Table(name = "sms_automation")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class SmsAutomation {

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
