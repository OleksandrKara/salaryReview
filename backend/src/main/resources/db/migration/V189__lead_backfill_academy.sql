-- One-time: PMU academy course requests become CRM leads (channel Academy). Idempotent through external_id.
INSERT INTO lead_intake (business_id, channel, source, traffic_source, phone_number, email, name, detail,
                         external_id, outcome, marked, created_at, payload)
SELECT a.business_id, 'Academy',
       CASE WHEN a.gclid IS NOT NULL AND a.gclid <> '' THEN 'Google Ads'
            WHEN lower(COALESCE(a.utm_source, '')) LIKE '%instagram%' THEN 'Instagram'
            WHEN lower(COALESCE(a.utm_source, '')) LIKE '%facebook%' THEN 'Facebook'
            WHEN lower(COALESCE(a.utm_source, '')) LIKE '%google%' THEN 'Google organic'
            ELSE 'Unknown' END,
       CASE WHEN a.gclid IS NOT NULL AND a.gclid <> '' THEN 'Google Ads' ELSE NULLIF(a.utm_source, '') END,
       CASE WHEN length(regexp_replace(COALESCE(a.phone, ''), '\D', '', 'g')) = 10 THEN '+1' || regexp_replace(a.phone, '\D', '', 'g')
            WHEN length(regexp_replace(COALESCE(a.phone, ''), '\D', '', 'g')) = 11 AND regexp_replace(a.phone, '\D', '', 'g') LIKE '1%'
                 THEN '+' || regexp_replace(a.phone, '\D', '', 'g')
            ELSE NULL END,
       NULLIF(lower(trim(a.email)), ''), a.name, a.course_name, 'academy:' || a.id::text, 'MATCHED', FALSE, a.created_at,
       jsonb_strip_nulls(jsonb_build_object('courses', a.course_name, 'message', NULLIF(a.message, ''),
                                            'pageUrl', a.source_page_url, 'utmSource', NULLIF(a.utm_source, ''),
                                            'utmCampaign', NULLIF(a.utm_campaign, '')))::text
FROM academy_lead a
WHERE a.kind = 'course_request' AND a.business_id IN (1, 2)
  AND (NULLIF(trim(a.email), '') IS NOT NULL OR length(regexp_replace(COALESCE(a.phone, ''), '\D', '', 'g')) >= 10)
ON CONFLICT DO NOTHING;
