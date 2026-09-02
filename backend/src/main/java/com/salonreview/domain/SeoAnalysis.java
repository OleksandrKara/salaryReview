package com.salonreview.domain;

import com.salonreview.ai.SeoAnalysisResult;
import com.salonreview.ai.SeoAnalysisResult.OverallStatus;
import com.salonreview.ai.SeoAnalysisResult.Recommendation;
import com.salonreview.seo.SeoAnalysisSnapshot;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Cached LLM SEO analysis for one (business, prompt version, data snapshot) combination — mirrors
 * {@link FunnelAnalysis}'s caching shape exactly (seo-intelligence-advisor design.md D7/D8).
 * {@code snapshotFingerprint} is a deterministic string built from the exact numbers in {@code
 * dataSnapshot}; a repeat "Analyze SEO" click with unchanged underlying data returns the cached
 * row instead of calling Claude again. Rows are never overwritten/updated — every analysis is a
 * new row, so the owner can always open a past analysis and see exactly what was recommended at
 * that time (design.md's "historical AI recommendations" requirement).
 */
@Entity
@Table(name = "seo_analysis")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class SeoAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false)
    private Long businessId;

    @Column(name = "prompt_version", nullable = false, length = 32)
    private String promptVersion;

    @Column(name = "snapshot_fingerprint", nullable = false, columnDefinition = "text")
    private String snapshotFingerprint;

    /** Which language the LLM was instructed to write this analysis in — part of the cache lookup,
     * same reasoning as {@link FunnelAnalysis#getLanguage()}. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Language language;

    /** The full structured snapshot the LLM actually saw — stored verbatim so a historical
     * analysis can be reconstructed exactly, independent of what the live dashboard shows today
     * (design.md D8). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data_snapshot", nullable = false, columnDefinition = "jsonb")
    private SeoAnalysisSnapshot dataSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(name = "overall_status", nullable = false, length = 32)
    private OverallStatus overallStatus;

    @Column(name = "executive_summary", nullable = false, columnDefinition = "text")
    private String executiveSummary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "wins_json", nullable = false, columnDefinition = "jsonb")
    private java.util.List<String> wins;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "problems_json", nullable = false, columnDefinition = "jsonb")
    private java.util.List<String> problems;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "recommendations_json", nullable = false, columnDefinition = "jsonb")
    private java.util.List<Recommendation> recommendations;

    @Column(nullable = false, length = 64)
    private String model;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
