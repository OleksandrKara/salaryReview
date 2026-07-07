package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Manually-entered ad spend for one calendar month — there's no Meta/Google Ads API integration, so
 * the owner (or an Ads Manager account) types in what's been spent so far this month, for the
 * Marketing Analytics ROI card. Absence of a row for a (year, month) means "not entered yet" (treat
 * as zero).
 */
@Entity
@Table(name = "ad_spend", uniqueConstraints = @UniqueConstraint(columnNames = {"year", "month"}))
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class AdSpend {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Integer year;

    @Column(nullable = false)
    private Integer month;

    @Column(name = "amount_spent", nullable = false, precision = 10, scale = 2)
    private BigDecimal amountSpent;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
