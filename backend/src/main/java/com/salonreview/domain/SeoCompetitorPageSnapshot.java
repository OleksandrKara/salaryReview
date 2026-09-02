package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One (competitor, date, strategy) PageSpeed Insights run for a competitor's website — same shape
 * as {@link SeoPageSnapshot}, since PageSpeed Insights scores any public URL for free, not just
 * the owner's own site (seo-intelligence-advisor Phase 7, design.md D9). {@link #lcpMs}/{@link
 * #cls}/{@link #fcpMs}/{@link #tbtMs} are nullable for the same reason as {@code
 * SeoPageSnapshot}'s own fields — a failed or partial Lighthouse run may still return a
 * performance score without every diagnostic populated.
 */
@Entity
@Table(name = "seo_competitor_page_snapshot")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class SeoCompetitorPageSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "competitor_id", nullable = false)
    private Long competitorId;

    @Column(nullable = false)
    private LocalDate date;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SeoPageSnapshot.Strategy strategy;

    @Column(name = "performance_score", nullable = false)
    private Integer performanceScore;

    @Column(name = "lcp_ms")
    private Integer lcpMs;

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
}
