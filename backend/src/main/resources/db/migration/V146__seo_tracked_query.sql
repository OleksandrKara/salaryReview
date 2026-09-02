-- seo-monitoring-dashboard follow-up: owner-curated "queries we actually want to rank for", not
-- just whatever Search Console happens to already be showing impressions for. Position-change
-- tracking on the dashboard is computed only for these (falling back to an auto-suggested top-N
-- by impressions when a business hasn't pinned any yet — see SeoDashboardService).
CREATE TABLE seo_tracked_query (
    id          BIGSERIAL PRIMARY KEY,
    business_id BIGINT NOT NULL REFERENCES business(id),
    query       TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (business_id, query)
);
