package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A manager's hourly pay rate (USD/hour). Only owners set it (enforced in the API). Keyed by the
 * manager's {@code app_user} id; absence of a row means "rate not set yet".
 */
@Entity
@Table(name = "manager_pay_rate")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class ManagerPayRate {

    @Id
    @Column(name = "user_id")
    private Long userId;

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
