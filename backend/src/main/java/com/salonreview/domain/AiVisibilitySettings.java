package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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

    /** "lat,lng,zoom" the Google engines search from (V169); null = city-level {@link #locationHint}. */
    @Column(name = "search_coordinate")
    private String searchCoordinate;

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
