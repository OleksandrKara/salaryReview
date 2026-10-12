package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** One question asked to one AI engine in one run, parsed (design.md D2/D4). Raw provider JSON is
 * deliberately not stored; {@link #answerExcerpt} plus the parsed lists are the history. */
@Entity
@Table(name = "ai_visibility_check")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class AiVisibilityCheck {

    public enum Engine { CHATGPT, GOOGLE_AI_MODE, GEMINI, PERPLEXITY, CLAUDE }

    public enum Status { OK, NO_ANSWER, ERROR }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false)
    private Long businessId;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "query_id", nullable = false)
    private Long queryId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Engine engine;

    private String model;

    @Column(name = "checked_at", nullable = false)
    private Instant checkedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    private Boolean mentioned;

    @Column(name = "mention_rank")
    private Short mentionRank;

    @Column(name = "own_site_cited")
    private Boolean ownSiteCited;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "places_named", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<String> placesNamed = new ArrayList<>();

    /** [{"domain": "...", "title": "...", "url": "..."}] */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sources_cited", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<Map<String, String>> sourcesCited = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sub_queries", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<String> subQueries = new ArrayList<>();

    @Column(name = "answer_excerpt")
    private String answerExcerpt;

    @Column(name = "cost_usd")
    private BigDecimal costUsd;

    private String error;

    @PrePersist
    void prePersist() {
        if (checkedAt == null) checkedAt = Instant.now();
    }
}
