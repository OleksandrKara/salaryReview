-- One row per LLM-generated booking-funnel analysis. Keyed by (landing page, flow, prompt
-- version, snapshot fingerprint) so a repeat "Analyze" click with unchanged underlying data
-- returns the cached row and never re-calls the LLM, while any real change to the funnel (new
-- events recorded) produces a different fingerprint and triggers a fresh analysis. A prompt
-- version bump naturally invalidates every previously-cached row too.
CREATE TABLE funnel_analysis (
    id                          BIGSERIAL PRIMARY KEY,
    landing_page_slug           VARCHAR(64)  NOT NULL,
    flow_key                    VARCHAR(64)  NOT NULL,
    prompt_version              VARCHAR(32)  NOT NULL,
    snapshot_fingerprint        TEXT         NOT NULL,
    biggest_bottleneck_step     VARCHAR(64)  NOT NULL,
    bottleneck_explanation      TEXT         NOT NULL,
    recommendations_json        JSONB        NOT NULL,
    suspicious_patterns_json    JSONB        NOT NULL,
    suggested_ab_tests_json     JSONB        NOT NULL,
    top_priority_action         TEXT         NOT NULL,
    model                       VARCHAR(64)  NOT NULL,
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_funnel_analysis_lookup
    ON funnel_analysis (landing_page_slug, flow_key, prompt_version, created_at DESC);
