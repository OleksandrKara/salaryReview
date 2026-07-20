package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Manually-entered ad spend for one landing page over an arbitrary date range — replaces the old
 * single-figure-per-calendar-month {@code ad_spend} table. The owner's budget varies week to week,
 * and now that Ads Report is scoped per landing page, spend needs the same scoping (see
 * openspec/changes/ads-report-consolidation/design.md D2). No uniqueness constraint: a corrected
 * re-entry is kept alongside the original rather than silently overwriting it, so spend history
 * stays auditable — see {@code MarketingAnalyticsService}'s prorate-and-sum resolution logic for how
 * overlapping/multiple entries combine for any requested report period.
 */
@Entity
@Table(name = "ad_spend_entries")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class AdSpendEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "landing_page_slug", nullable = false)
    private String landingPageSlug;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(name = "amount_spent", nullable = false, precision = 10, scale = 2)
    private BigDecimal amountSpent;

    @Column(name = "entered_by", length = 100)
    private String enteredBy;

    @Column(name = "entered_at", nullable = false)
    private Instant enteredAt;

    @PrePersist
    void touch() {
        if (enteredAt == null) enteredAt = Instant.now();
    }
}
