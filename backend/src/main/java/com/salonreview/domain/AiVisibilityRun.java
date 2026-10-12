package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/** One weekly (or single-question manual) pass over a business's questions (design.md D3). */
@Entity
@Table(name = "ai_visibility_run")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class AiVisibilityRun {

    public enum Trigger { SCHEDULED, MANUAL_QUERY }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false)
    private Long businessId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Trigger trigger;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "checks_ok", nullable = false)
    private int checksOk;

    @Column(name = "checks_failed", nullable = false)
    private int checksFailed;

    @Column(name = "cost_usd", nullable = false)
    @Builder.Default
    private BigDecimal costUsd = BigDecimal.ZERO;

    @Column(name = "stopped_reason")
    private String stoppedReason;

    @PrePersist
    void prePersist() {
        if (startedAt == null) startedAt = Instant.now();
    }
}
