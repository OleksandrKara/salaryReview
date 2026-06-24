package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Versioned configuration for the answering agent. Owner-tunable at runtime: each update inserts a
 * new version (the {@code version} is the primary key) rather than mutating the active row, and
 * exactly one row is {@code active} at a time. Every answer records which version produced it.
 */
@Entity
@Table(name = "rag_agent_config")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class RagAgentConfig {

    @Id
    private Integer version;

    @Column(name = "system_prompt", nullable = false, columnDefinition = "text")
    private String systemPrompt;

    @Column(nullable = false, length = 64)
    private String model;

    /** Valid on Haiku 4.5 / Sonnet 4.6. Removed (400) on Opus 4.7+/Fable — model-dependent knob. */
    @Column(nullable = false, precision = 3, scale = 2)
    private BigDecimal temperature;

    @Column(nullable = false)
    private Integer k;

    @Column(name = "distance_threshold", nullable = false, precision = 4, scale = 3)
    private BigDecimal distanceThreshold;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
