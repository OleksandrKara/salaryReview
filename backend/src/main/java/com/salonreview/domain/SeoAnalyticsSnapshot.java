package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One (business, date) row pulled from the GA4 Data API — seo-monitoring-dashboard follow-up.
 * {@link #getTotalUsers()}/{@link #getNewUsers()} are site-wide (every channel, not just organic —
 * a true GA4 distinct-user count can't be summed across a channel breakdown without double-
 * counting a user who arrived via two channels the same day, so these come from an un-broken-down
 * report); {@link #getOrganicSessions()} is specifically the "Organic Search" channel-group slice,
 * the SEO-attributable part of that traffic.
 */
@Entity
@Table(name = "seo_analytics_snapshot")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class SeoAnalyticsSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false)
    private Long businessId;

    @Column(name = "date", nullable = false)
    private LocalDate date;

    @Column(name = "total_users", nullable = false)
    private Integer totalUsers;

    @Column(name = "new_users", nullable = false)
    private Integer newUsers;

    @Column(name = "organic_sessions", nullable = false)
    private Integer organicSessions;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
