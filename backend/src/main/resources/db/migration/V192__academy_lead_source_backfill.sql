-- Academy leads never show "Unknown": AI referrers get their own label, leads with no data are Direct.
UPDATE lead_intake SET source = 'AI search'
WHERE channel = 'Academy' AND NOT source_manual AND lower(COALESCE(traffic_source, '')) ~ 'chatgpt|openai|perplexity|gemini|copilot|claude';

DO $do$
BEGIN
  IF to_regclass('marketing.contacts') IS NOT NULL THEN
    EXECUTE $q$
UPDATE lead_intake l SET traffic_source = c.ts,
  source = CASE
    WHEN lower(c.ts) LIKE 'google ads%' THEN 'Google Ads'
    WHEN lower(c.ts) LIKE 'meta ads%' THEN 'Meta Ads'
    WHEN lower(c.ts) LIKE '%instagram%' THEN 'Instagram'
    WHEN lower(c.ts) LIKE '%facebook%' THEN 'Facebook'
    WHEN lower(c.ts) LIKE '%google%' THEN 'Google organic'
    WHEN lower(c.ts) ~ 'bing|yahoo' THEN 'Search organic'
    WHEN lower(c.ts) LIKE 'direct%' THEN 'Direct'
    ELSE 'Other' END
FROM (SELECT DISTINCT ON (l2.id) l2.id, COALESCE(c.original_traffic_source, c.marketing_traffic_source) AS ts
      FROM lead_intake l2 JOIN marketing.contacts c ON c.business_id = l2.business_id
        AND (c.phone_number = l2.phone_number OR (l2.email IS NOT NULL AND lower(c.email_address) = lower(l2.email)))
      WHERE l2.channel = 'Academy' AND l2.source = 'Unknown' AND NOT l2.source_manual
        AND COALESCE(c.original_traffic_source, c.marketing_traffic_source, '') <> ''
      ORDER BY l2.id, c.created_at) c
WHERE l.id = c.id
    $q$;
  END IF;
END
$do$;

UPDATE lead_intake SET source = 'Direct', traffic_source = COALESCE(traffic_source, 'Direct')
WHERE channel = 'Academy' AND source = 'Unknown' AND NOT source_manual;
