-- seo-monitoring-dashboard follow-up: GA4 was already authenticated for and queried per-page
-- (GoogleAnalyticsClient.pageViewsByPath, Phase 3) but the result was never persisted or shown on
-- the dashboard — this table + SeoSyncService#syncAnalytics closes that gap with the two numbers
-- the owner actually asked to see: total unique users (site-wide, all channels) and organic-search
-- sessions specifically (the SEO-attributable slice of that traffic). Same one-row-per-business-
-- per-day shape as seo_page_snapshot/seo_search_metrics_snapshot.
CREATE TABLE seo_analytics_snapshot (
    id                BIGSERIAL PRIMARY KEY,
    business_id       BIGINT NOT NULL REFERENCES business(id),
    date              DATE NOT NULL,
    total_users       INT NOT NULL,
    new_users         INT NOT NULL,
    organic_sessions  INT NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (business_id, date)
);
