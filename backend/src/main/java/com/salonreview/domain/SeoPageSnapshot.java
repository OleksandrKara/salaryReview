package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One (business, date, url, strategy) PageSpeed Insights run — seo-monitoring-dashboard design.md
 * D2. {@link #lcpMs}/{@link #cls}/{@link #fcpMs}/{@link #tbtMs} are nullable since a failed or
 * partial Lighthouse run (transient PSI errors are common — see the 2026-09-01 manual testing
 * notes) may still return a performance score without every diagnostic populated.
 */
@Entity
@Table(name = "seo_page_snapshot")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class SeoPageSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false)
    private Long businessId;

    @Column(name = "date", nullable = false)
    private LocalDate date;

    @Column(name = "url", nullable = false)
    private String url;

    @Enumerated(EnumType.STRING)
    @Column(name = "strategy", nullable = false)
    private Strategy strategy;

    @Column(name = "performance_score", nullable = false)
    private Integer performanceScore;

    @Column(name = "lcp_ms")
    private Integer lcpMs;

    @Column(name = "cls")
    private BigDecimal cls;

    @Column(name = "fcp_ms")
    private Integer fcpMs;

    @Column(name = "tbt_ms")
    private Integer tbtMs;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public enum Strategy {
        MOBILE, DESKTOP
    }
}
