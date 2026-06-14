package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A daily revenue snapshot — written by the snapshot scheduler at 01:30 salon-local. Each row freezes
 * what the forecaster knew on that date: MTD revenue, the upcoming-booking pipeline, and (filled in
 * once the month closes) the actual month-end total. The {@code (mtd_revenue + upcoming_gross, month_end_actual)}
 * pairs become the calibration dataset for the bias-correction step.
 */
@Entity
@Table(name = "revenue_snapshot")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class RevenueSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "snapshot_date", nullable = false, unique = true)
    private LocalDate snapshotDate;

    @Column(name = "mtd_revenue", nullable = false, precision = 10, scale = 2)
    private BigDecimal mtdRevenue;

    @Column(name = "mtd_card", nullable = false, precision = 10, scale = 2)
    private BigDecimal mtdCard;

    @Column(name = "mtd_cash", nullable = false, precision = 10, scale = 2)
    private BigDecimal mtdCash;

    @Column(name = "mtd_services", nullable = false)
    private int mtdServices;

    @Column(name = "upcoming_count", nullable = false)
    private int upcomingCount;

    @Column(name = "upcoming_gross", nullable = false, precision = 10, scale = 2)
    private BigDecimal upcomingGross;

    /** Sum of {@code PeriodEntry} revenue for this snapshot's month — null until the month closes. */
    @Column(name = "month_end_actual", precision = 10, scale = 2)
    private BigDecimal monthEndActual;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
