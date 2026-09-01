package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One (business, date, query, page) row pulled from Search Console's {@code searchAnalytics.query}
 * — seo-monitoring-dashboard design.md D2.
 */
@Entity
@Table(name = "seo_search_metrics_snapshot")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class SeoSearchMetricsSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false)
    private Long businessId;

    @Column(name = "date", nullable = false)
    private LocalDate date;

    @Column(name = "query", nullable = false)
    private String query;

    /** Nullable — a query-only row (no page dimension) when the sync doesn't request page-level
     * breakdown for a given query. */
    @Column(name = "page")
    private String page;

    @Column(name = "clicks", nullable = false)
    private Integer clicks;

    @Column(name = "impressions", nullable = false)
    private Integer impressions;

    @Column(name = "ctr", nullable = false)
    private BigDecimal ctr;

    @Column(name = "position", nullable = false)
    private BigDecimal position;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
