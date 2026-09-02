package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A metric that crossed one of Google's published Core Web Vitals thresholds, or the one
 * non-Google CTR heuristic — seo-monitoring-dashboard design.md D3. {@link #resolvedAt} is set
 * automatically the first time a later snapshot for the same business/metric falls back under
 * threshold (see {@code SeoIssueFlaggingService}), no manual dismiss for the Google-sourced types.
 */
@Entity
@Table(name = "seo_technical_issue")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class SeoTechnicalIssue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false)
    private Long businessId;

    @Enumerated(EnumType.STRING)
    @Column(name = "issue_type", nullable = false)
    private IssueType issueType;

    /** Human-readable recommendation text shown in the dashboard (design.md D3) — e.g. "Largest
     * Contentful Paint is 3.2s, above Google's 2.5s 'good' threshold." */
    @Column(name = "detail", nullable = false)
    private String detail;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false)
    private Severity severity;

    @Column(name = "metric_value")
    private BigDecimal metricValue;

    /** The page this issue is about (LCP/CLS/INP) — null only for issue types that aren't
     * page-scoped. Needed to auto-resolve the correct row once more than the homepage is tracked
     * (design.md Open Question 2). */
    @Column(name = "url")
    private String url;

    /** The search query this issue is about (CTR_OPPORTUNITY only). */
    @Column(name = "query")
    private String query;

    /** Mobile and desktop are independent Lighthouse runs against the same URL and can have
     * completely different pass/fail states for the same metric — without this, a "good" result on
     * one strategy auto-resolved the other, still-real, still-failing strategy's open issue for the
     * same (business, issue_type, url) (found live 2026-09-01). Null for {@code CTR_OPPORTUNITY},
     * which isn't strategy-scoped. */
    @Enumerated(EnumType.STRING)
    @Column(name = "strategy")
    private SeoPageSnapshot.Strategy strategy;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @PrePersist
    void prePersist() {
        if (firstSeenAt == null) firstSeenAt = Instant.now();
    }

    public enum IssueType {
        LCP, CLS, INP, FCP, TBT, CTR_OPPORTUNITY
    }

    /** {@code ADVISORY} is only used by {@code CTR_OPPORTUNITY} — a real signal, but not sourced
     * from a Google-published pass/fail threshold the way LCP/CLS/INP are (design.md D3). */
    public enum Severity {
        NEEDS_IMPROVEMENT, POOR, ADVISORY
    }
}
