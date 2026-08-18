package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Cached LLM triage for a flagged suspicious booking. One row per
 * {@code (square_booking_id, prompt_version)} pair: repeat calls under the same prompt version
 * return the cached row without hitting the LLM. A new prompt version triggers a fresh triage and
 * preserves the old row for eval comparison.
 *
 * <p>Owner feedback (helpful / corrected classification) is written back to the same row by the
 * feedback endpoint and shipped to LangSmith as a graded run.
 */
@Entity
@Table(name = "suspicious_triage",
       uniqueConstraints = @UniqueConstraint(columnNames = {"business_id", "square_booking_id", "prompt_version"}))
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class SuspiciousTriage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false)
    private Long businessId;

    @Column(name = "square_booking_id", nullable = false)
    private String squareBookingId;

    @Column(name = "prompt_version", nullable = false, length = 32)
    private String promptVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TriageClassification classification;

    @Column(nullable = false, precision = 4, scale = 3)
    private BigDecimal confidence;

    @Column(nullable = false, columnDefinition = "text")
    private String explanation;

    @Column(name = "draft_message", nullable = false, columnDefinition = "text")
    private String draftMessage;

    /** List of detection-signal names the explanation cites (e.g. ["late_checkout", "zero_tip"]). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "signals_json", nullable = false, columnDefinition = "jsonb")
    private List<String> signals;

    @Column(nullable = false, length = 64)
    private String model;

    @Column(name = "langsmith_run_id", length = 64)
    private String langsmithRunId;

    /** Populated when the LLM returned {@code stop_reason: refusal} instead of a real triage. */
    @Column(name = "refusal_category", length = 64)
    private String refusalCategory;

    /** Owner's explicit feedback: true = thumbs-up, false = thumbs-down, null = no feedback yet. */
    private Boolean helpful;

    /** Owner's corrected classification on a thumbs-down; null when no correction was provided. */
    @Enumerated(EnumType.STRING)
    @Column(name = "corrected_classification", length = 32)
    private TriageClassification correctedClassification;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
