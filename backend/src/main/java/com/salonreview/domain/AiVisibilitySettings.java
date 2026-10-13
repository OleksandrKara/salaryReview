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

/** Per-business AI-visibility config (openspec/changes/ai-visibility-monitoring design.md D2):
 * which names and domains count as "the assistant recommended us", the monthly DataForSEO budget,
 * and the digest switch. {@link #nextActions} is the 1-3 line "in progress" note Claude sets through
 * the internal API after reading the weekly numbers (D8). */
@Entity
@Table(name = "ai_visibility_settings")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class AiVisibilitySettings {

    @Id
    @Column(name = "business_id")
    private Long businessId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "brand_aliases", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<String> brandAliases = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "own_domains", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<String> ownDomains = new ArrayList<>();

    @Column(name = "location_hint", nullable = false)
    private String locationHint;

    /** Superseded by {@link #searchPoints} (V170); kept as a fallback when no points are set. */
    @Column(name = "search_coordinate")
    private String searchCoordinate;

    /** Where the Google engines search from: [{"label", "coordinate" ("lat,lng,zoom"), "clientShare"?}].
     * The first point is used for ordinary questions; "all points" questions run from each (V170). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "search_points", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<Map<String, Object>> searchPoints = new ArrayList<>();

    @Column(name = "monthly_budget_usd", nullable = false)
    private BigDecimal monthlyBudgetUsd;

    @Column(name = "digest_enabled", nullable = false)
    private boolean digestEnabled;

    @Column(name = "next_actions")
    private String nextActions;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
