package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Latest component snapshot of one page (ours or a competitor's) for the page comparison
 * (design.md D10). {@link #components}: component key → {"present": bool, "evidence": "..."};
 * {@link #componentsHistory} keeps the last 6 earlier snapshots. */
@Entity
@Table(name = "ai_visibility_page")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class AiVisibilityPage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false)
    private Long businessId;

    @Column(nullable = false)
    private String url;

    @Column(name = "is_own", nullable = false)
    private boolean own;

    @Column(name = "last_fetched_at")
    private Instant lastFetchedAt;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "fetch_method")
    private String fetchMethod;

    @Column(name = "content_hash")
    private String contentHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Map<String, Object>> components = new LinkedHashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> metrics = new LinkedHashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "components_history", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<Map<String, Object>> componentsHistory = new ArrayList<>();

    private String error;
}
