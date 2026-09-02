-- seo-intelligence-advisor Phase 6 (design.md D7/D8): one row per LLM-generated SEO analysis,
-- mirroring funnel_analysis's own caching shape exactly. snapshot_fingerprint is a deterministic
-- string built from exactly the numbers in data_snapshot; a repeat "Analyze SEO" click with
-- unchanged underlying data returns the cached row instead of calling Claude again. data_snapshot
-- stores the FULL structured snapshot the LLM actually saw (design.md D8 — "store the structured
-- snapshot, not just the final response"), so a historical analysis can always be reconstructed
-- exactly, independent of what the live dashboard shows today.
CREATE TABLE seo_analysis (
    id                      BIGSERIAL PRIMARY KEY,
    business_id             BIGINT       NOT NULL REFERENCES business(id),
    prompt_version          VARCHAR(32)  NOT NULL,
    snapshot_fingerprint    TEXT         NOT NULL,
    language                TEXT         NOT NULL,
    data_snapshot           JSONB        NOT NULL,
    overall_status          TEXT         NOT NULL,
    executive_summary       TEXT         NOT NULL,
    wins_json               JSONB        NOT NULL,
    problems_json           JSONB        NOT NULL,
    recommendations_json    JSONB        NOT NULL,
    model                   VARCHAR(64)  NOT NULL,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_seo_analysis_lookup
    ON seo_analysis (business_id, prompt_version, created_at DESC);
