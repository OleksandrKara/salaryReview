package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A write-once "I have read and agree to follow this SOP" signature, keyed to a specific
 * {@link SopVersion} (so publishing a new version requires a fresh acknowledgment). Never edited or
 * deleted. UNIQUE(sop_version_id, user_id) makes acknowledge idempotent.
 */
@Entity
@Table(name = "sop_acknowledgments")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class SopAcknowledgment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sop_version_id", nullable = false)
    private Long sopVersionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "acknowledged_at", nullable = false)
    private Instant acknowledgedAt;

    @PrePersist
    void prePersist() {
        if (acknowledgedAt == null) acknowledgedAt = Instant.now();
    }
}
