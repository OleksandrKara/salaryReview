package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A public site's Microsoft Clarity tracking-code project id — one row per real hostname (see
 * V145), not per business, since a single business can own more than one public site
 * (akluxnails.com and mani.akluxnails.com are both business 1). Owner-editable at
 * {@code /owner/settings/tracking}; read cross-app by akluxnails-home/salonLandings via
 * {@code GET /api/internal/tracking-config?domain=...} (see {@code InternalTrackingController}).
 * Not a secret — a Clarity project id is visible in any page's own rendered source once live — so
 * no encryption-at-rest, unlike {@code SquareConnection}/{@code SeoConnection}.
 */
@Entity
@Table(name = "tracking_config")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class TrackingConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false)
    private Long businessId;

    @Column(name = "hostname", nullable = false, unique = true)
    private String hostname;

    @Column(name = "clarity_project_id")
    private String clarityProjectId;

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
