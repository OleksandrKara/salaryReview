package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A manager's hourly pay rate (USD/hour) effective from a date. A manager has a series of these;
 * the rate for a work date is the latest row with {@code effectiveFrom <= date}. Only owners set
 * rates (enforced in the API); no rows means "rate not set yet".
 */
@Entity
@Table(name = "manager_pay_rate_history")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class ManagerPayRate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "usd_per_hour", nullable = false, precision = 8, scale = 2)
    private BigDecimal usdPerHour;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
