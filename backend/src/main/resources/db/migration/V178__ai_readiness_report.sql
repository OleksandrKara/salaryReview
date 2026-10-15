-- Weekly AI-readiness check of our own sites (Notion #26, 2026-10-15): robots.txt open to AI
-- crawlers, llms.txt, and every tracked question's target page readable without JavaScript with
-- structured data. One row per business per run; AiReadinessService.
CREATE TABLE ai_readiness_report (
    id          BIGSERIAL PRIMARY KEY,
    business_id BIGINT      NOT NULL REFERENCES business (id),
    checked_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    checks      JSONB       NOT NULL,
    cost_usd    NUMERIC(8, 4) NOT NULL DEFAULT 0
);
CREATE INDEX ai_readiness_report_business_idx ON ai_readiness_report (business_id, checked_at DESC);
