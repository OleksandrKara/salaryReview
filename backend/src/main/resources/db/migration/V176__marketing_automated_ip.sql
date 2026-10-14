-- Traffic from IPs that look automated (Notion #51, 2026-10-14): 5+ different visitor ids in 30
-- days and not one action beyond page views (no click, funnel step or submission). 23-26% of
-- "visitors" were coming from ~100 such IPs, diluting every conversion rate. Recomputed daily by
-- AutomatedIpScheduler; TrafficSourceSql.notAutomated excludes them from the dashboards.
CREATE TABLE marketing_automated_ip (
    ip_address  TEXT PRIMARY KEY,
    visitors    INTEGER     NOT NULL,
    detected_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Filled by AutomatedIpScheduler (marketing.* lives in the salonLandings-managed schema, which
-- this repo's test database does not have, so no initial INSERT here).
