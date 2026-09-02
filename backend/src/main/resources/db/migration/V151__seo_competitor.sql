-- seo-intelligence-advisor Phase 7 (design.md D9, redesigned 2026-09-02 to a zero-cost scope):
-- owner-curated competitor list. gbp_rating/gbp_review_count/gbp_updated_at are owner-entered
-- directly, never auto-synced — no free API supplies a competitor's own Google Business Profile
-- data. seo_competitor_page_snapshot mirrors seo_page_snapshot's own shape exactly, since
-- PageSpeed Insights (already integrated, free) scores any public URL, not just the owner's own.
CREATE TABLE seo_competitor (
    id                BIGSERIAL PRIMARY KEY,
    business_id       BIGINT NOT NULL REFERENCES business(id),
    name              TEXT NOT NULL,
    website           TEXT NOT NULL,
    location          TEXT,
    notes             TEXT,
    active            BOOLEAN NOT NULL DEFAULT true,
    gbp_rating        NUMERIC(2,1),
    gbp_review_count  INTEGER,
    gbp_updated_at    TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE seo_competitor_page_snapshot (
    id                 BIGSERIAL PRIMARY KEY,
    competitor_id      BIGINT NOT NULL REFERENCES seo_competitor(id),
    date               DATE NOT NULL,
    strategy           TEXT NOT NULL CHECK (strategy IN ('MOBILE', 'DESKTOP')),
    performance_score  INTEGER NOT NULL,
    lcp_ms             INTEGER,
    cls                NUMERIC(6,4),
    fcp_ms             INTEGER,
    tbt_ms             INTEGER,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (competitor_id, date, strategy)
);

CREATE INDEX idx_seo_competitor_page_snapshot_competitor_date
    ON seo_competitor_page_snapshot (competitor_id, date DESC);
