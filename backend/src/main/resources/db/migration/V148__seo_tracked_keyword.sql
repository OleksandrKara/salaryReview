-- seo-intelligence-advisor Phase 4 (design.md D1/D3): owner-curated keyword rank-tracking list,
-- distinct from seo_tracked_query (Search-Console-impressions-derived, no real SERP check) and
-- from seo_search_metrics_snapshot (Google's own blended average position). location is a
-- required, first-class column (not a UI default) so "Downtown San Diego" vs "San Diego metro"
-- is never conflated once real rank checks exist (Phase 5) — see design.md D3's own reasoning.
-- No rank data yet in this phase; seo_rank_snapshot arrives in Phase 5 once an external provider
-- is connected.
CREATE TABLE seo_tracked_keyword (
    id          BIGSERIAL PRIMARY KEY,
    business_id BIGINT NOT NULL REFERENCES business(id),
    keyword     TEXT NOT NULL,
    target_url  TEXT,
    location    TEXT NOT NULL,
    device      TEXT NOT NULL,
    active      BOOLEAN NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (business_id, keyword, location, device)
);
