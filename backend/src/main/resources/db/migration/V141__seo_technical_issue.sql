-- seo-monitoring-dashboard Phase 1 (design.md D3) — rule-based (not ML) flagging when a metric
-- crosses one of Google's own published Core Web Vitals thresholds, or the one non-Google CTR
-- heuristic (clearly distinguished by issue_type, see SeoIssueFlaggingService). An issue
-- auto-resolves (resolved_at set) the first time a later snapshot falls back under threshold.
CREATE TABLE seo_technical_issue (
    id            BIGSERIAL PRIMARY KEY,
    business_id   BIGINT NOT NULL REFERENCES business(id),
    issue_type    TEXT NOT NULL CHECK (issue_type IN ('LCP', 'CLS', 'INP', 'CTR_OPPORTUNITY')),
    detail        TEXT NOT NULL,
    severity      TEXT NOT NULL CHECK (severity IN ('NEEDS_IMPROVEMENT', 'POOR', 'ADVISORY')),
    metric_value  NUMERIC(10,4),
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at   TIMESTAMPTZ
);

CREATE INDEX idx_seo_technical_issue_active
    ON seo_technical_issue (business_id, resolved_at) WHERE resolved_at IS NULL;
