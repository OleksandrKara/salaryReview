package com.salonreview.domain;

import com.salonreview.ai.FunnelAnalysisResult.PrioritizedRecommendation;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;

/**
 * Cached LLM funnel analysis for one (landing page, flow, prompt version, data snapshot)
 * combination — mirrors {@link SuspiciousTriage}'s caching shape. {@code snapshotFingerprint} is
 * a deterministic string built from the exact funnel numbers analyzed (see
 * {@code FunnelAnalysisService#fingerprint}); a repeat "Analyze" click with unchanged underlying
 * data returns the cached row instead of calling Claude again, while any real change to the
 * funnel (new events recorded) naturally produces a different fingerprint and triggers a fresh
 * analysis.
 */
@Entity
@Table(name = "funnel_analysis")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class FunnelAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "landing_page_slug", nullable = false, length = 64)
    private String landingPageSlug;

    @Column(name = "flow_key", nullable = false, length = 64)
    private String flowKey;

    @Column(name = "prompt_version", nullable = false, length = 32)
    private String promptVersion;

    @Column(name = "snapshot_fingerprint", nullable = false, columnDefinition = "text")
    private String snapshotFingerprint;

    /** Which language the LLM was instructed to write this analysis in — part of the cache lookup
     * so an EN-preferring and RU-preferring owner never share a cached result for the same
     * snapshot. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Language language;

    @Column(name = "biggest_bottleneck_step", nullable = false, length = 64)
    private String biggestBottleneckStep;

    @Column(name = "bottleneck_explanation", nullable = false, columnDefinition = "text")
    private String bottleneckExplanation;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "recommendations_json", nullable = false, columnDefinition = "jsonb")
    private List<PrioritizedRecommendation> recommendations;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "suspicious_patterns_json", nullable = false, columnDefinition = "jsonb")
    private List<String> suspiciousPatterns;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "suggested_ab_tests_json", nullable = false, columnDefinition = "jsonb")
    private List<String> suggestedAbTests;

    @Column(name = "top_priority_action", nullable = false, columnDefinition = "text")
    private String topPriorityAction;

    @Column(nullable = false, length = 64)
    private String model;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
