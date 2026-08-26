package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * The tenant root — see openspec/changes/multi-tenant-salon-platform/design.md D1. Every
 * business-owned table carries a business_id (direct column or inherited via FK); this is the row
 * that id points at. The existing salon (short_code "akluxnails") is Business A, backfilled by V84.
 */
@Entity
@Table(name = "business")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Business {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "short_code", nullable = false, unique = true)
    private String shortCode;

    @Column(nullable = false)
    private String timezone;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** The hostname salonLandings serves this business's public landing page on (e.g.
     * {@code mani.akluxnails.com}) — resolved via {@code GET /api/internal/businesses/by-domain},
     * see ~/salonLandings/docs/multi-tenant-akpmu-design.md. Null until a landing page is set up
     * for this business. */
    @Column(name = "public_domain")
    private String publicDomain;

    /** Owner-set destination for the {@code checkout_review_request} automation's positive-reply
     * branch — see {@link com.salonreview.sms.CheckoutReviewLinks}. Null/blank means that
     * automation is treated as not configured for this business (see
     * {@code CheckoutReviewTriggerService}), never a fallback to some other business's page. */
    @Column(name = "google_review_url")
    private String googleReviewUrl;

    /** Owner-set destination for the {@code checkout_review_request} automation's positive-reply
     * branch once a customer has already clicked through to {@link #googleReviewUrl} before — see
     * {@link com.salonreview.sms.CheckoutReviewLinks}. Same null/blank convention as that field. */
    @Column(name = "yelp_review_url")
    private String yelpReviewUrl;

    /** Same as {@link #googleReviewUrl}, for the final branch — a customer who's already clicked
     * through to both {@link #googleReviewUrl} and {@link #yelpReviewUrl} before. */
    @Column(name = "feedback_form_url")
    private String feedbackFormUrl;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
