-- Traffic from IPs that look automated (Notion #51, 2026-10-14): 5+ different visitor ids in 30
-- days and not one action beyond page views (no click, funnel step or submission). 23-26% of
-- "visitors" were coming from ~100 such IPs, diluting every conversion rate. Recomputed daily by
-- AutomatedIpScheduler; TrafficSourceSql.notAutomated excludes them from the dashboards.
CREATE TABLE marketing_automated_ip (
    ip_address  TEXT PRIMARY KEY,
    visitors    INTEGER     NOT NULL,
    detected_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO marketing_automated_ip (ip_address, visitors)
WITH v AS (SELECT ip_address AS ip, visitor_id FROM marketing.visits
           WHERE occurred_at > now() - interval '30 days' AND ip_address IS NOT NULL AND ip_address <> ''),
act AS (SELECT session_id AS vid FROM marketing.events WHERE event_type <> 'page_view' AND created_at > now() - interval '30 days'
        UNION SELECT session_id FROM marketing.funnel_events WHERE created_at > now() - interval '30 days'
        UNION SELECT visitor_id FROM marketing.submissions WHERE occurred_at > now() - interval '30 days' AND visitor_id IS NOT NULL)
SELECT ip, count(DISTINCT visitor_id) FROM v GROUP BY ip
HAVING count(DISTINCT visitor_id) >= 5
   AND count(DISTINCT visitor_id) FILTER (WHERE visitor_id IN (SELECT vid FROM act)) = 0;
