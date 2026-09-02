package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * An owner-curated competitor entry (seo-intelligence-advisor Phase 7, redesigned 2026-09-02 to a
 * zero-cost scope after the owner declined to pay for an external SEO data provider — see
 * design.md D9). {@code gbpRating}/{@code gbpReviewCount}/{@code gbpUpdatedAt} are owner-entered
 * directly (there's no free API for a competitor's own Google Business Profile data) and are never
 * touched by any scheduled sync — only {@link SeoCompetitorPageSnapshot} rows are automated, via
 * the existing PageSpeed Insights integration (which scores any public URL, not just the owner's
 * own site).
 */
@Entity
@Table(name = "seo_competitor")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class SeoCompetitor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false)
    private Long businessId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String website;

    private String location;

    private String notes;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "gbp_rating")
    private BigDecimal gbpRating;

    @Column(name = "gbp_review_count")
    private Integer gbpReviewCount;

    @Column(name = "gbp_updated_at")
    private Instant gbpUpdatedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
