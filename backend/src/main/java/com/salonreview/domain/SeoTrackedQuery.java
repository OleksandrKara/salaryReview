package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A query the owner has explicitly pinned as one they want to rank for — used to compute
 * position-change deltas on the dashboard (seo-monitoring-dashboard follow-up). Distinct from
 * {@link SeoSearchMetricsSnapshot#getQuery()}, which is every query Search Console happened to
 * see; this is the owner's own curated subset of those.
 */
@Entity
@Table(name = "seo_tracked_query")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class SeoTrackedQuery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false)
    private Long businessId;

    @Column(name = "query", nullable = false)
    private String query;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
