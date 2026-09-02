package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * An owner-curated keyword the business wants to rank for at a specific location/device — the
 * seed data for real SERP rank tracking (seo-intelligence-advisor design.md D1, Phase 5).
 * Deliberately distinct from {@link SeoTrackedQuery} (Search-Console-impressions-derived, no real
 * SERP check) and from {@link SeoSearchMetricsSnapshot#getQuery()} (Google's own blended average
 * position, not a single tracked rank) — see design.md D1/D3 for why these must never be
 * conflated. No rank data exists on this row itself; {@code seo_rank_snapshot} (Phase 5) is keyed
 * by this entity's id once a rank-tracking provider is connected.
 */
@Entity
@Table(name = "seo_tracked_keyword")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class SeoTrackedKeyword {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false)
    private Long businessId;

    @Column(name = "keyword", nullable = false)
    private String keyword;

    /** Nullable — the owner may not yet know (or may not have) one specific intended landing
     * page for a keyword; ranking history still tracks fine without it. */
    @Column(name = "target_url")
    private String targetUrl;

    /** Required, first-class (design.md D3) — e.g. "Downtown San Diego" or "92101," never
     * defaulted-and-forgotten inside a provider call. */
    @Column(name = "location", nullable = false)
    private String location;

    @Enumerated(EnumType.STRING)
    @Column(name = "device", nullable = false)
    private Device device;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public enum Device {
        MOBILE, DESKTOP
    }
}
