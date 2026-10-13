package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** One question the owner tracks across AI assistants (design.md D2). Only {@code ACTIVE} ones are
 * checked; {@code REMOVED} is a soft delete so the history stays readable. */
@Entity
@Table(name = "ai_visibility_query")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class AiVisibilityQuery {

    public enum Status { PROPOSED, ACTIVE, PAUSED, REMOVED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false)
    private Long businessId;

    @Column(name = "query_text", nullable = false)
    private String queryText;

    private String topic;

    @Column(name = "target_url")
    private String targetUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "activated_at")
    private Instant activatedAt;

    /** Engines this question is asked to; null = all. "near me" questions are Google-only (V170). */
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private java.util.List<String> engines;

    /** Asked from every search point instead of only the first (Google engines only). */
    @Column(name = "all_points", nullable = false)
    private boolean allPoints;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
