-- System archive "Outdated": every Square customer and website contact from before the CRM, one lead per person.
-- legacy marks rows that belong to the archive; the CHECK makes OUTDATED unreachable for anything else, so no
-- code path or manual edit can put a lead there. Leaving it (any other status) is allowed.
ALTER TABLE lead_intake ADD COLUMN legacy BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE lead_intake ADD CONSTRAINT lead_intake_outdated_only_legacy CHECK (status <> 'OUTDATED' OR legacy);

INSERT INTO lead_intake (business_id, channel, source, phone_number, email, name, detail, external_id,
                         square_customer_id, outcome, marked, status, legacy, created_at)
SELECT DISTINCT ON (c.business_id, c.k)
       c.business_id, 'Square', 'Unknown', c.phone, c.email, c.name, 'Square customer from before the CRM',
       'legacy:sq:' || c.square_customer_id, c.square_customer_id, 'MATCHED', FALSE, 'OUTDATED', TRUE,
       COALESCE(c.square_created_at, now())
FROM (
  SELECT sc.business_id, sc.square_customer_id, sc.square_created_at,
         CASE WHEN length(regexp_replace(COALESCE(sc.phone_number, ''), '\D', '', 'g')) >= 10 THEN sc.phone_number END AS phone,
         NULLIF(sc.email_address, '') AS email,
         NULLIF(trim(concat_ws(' ', sc.given_name, sc.family_name)), '') AS name,
         CASE WHEN length(regexp_replace(COALESCE(sc.phone_number, ''), '\D', '', 'g')) >= 10
              THEN right(regexp_replace(sc.phone_number, '\D', '', 'g'), 10) ELSE lower(NULLIF(sc.email_address, '')) END AS k
  FROM square_customer sc
  WHERE sc.business_id IN (1, 2)
) c
WHERE c.k IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM lead_intake l WHERE l.business_id = c.business_id AND l.square_customer_id = c.square_customer_id)
  AND NOT EXISTS (SELECT 1 FROM lead_intake l WHERE l.business_id = c.business_id AND c.phone IS NOT NULL
                  AND length(regexp_replace(COALESCE(l.phone_number, ''), '\D', '', 'g')) >= 10
                  AND right(regexp_replace(l.phone_number, '\D', '', 'g'), 10) = right(regexp_replace(c.phone, '\D', '', 'g'), 10))
  AND NOT EXISTS (SELECT 1 FROM lead_intake l WHERE l.business_id = c.business_id AND c.email IS NOT NULL AND lower(l.email) = lower(c.email))
ORDER BY c.business_id, c.k, c.square_created_at NULLS LAST
ON CONFLICT DO NOTHING;

-- Website contacts that never reached Square. marketing.contacts is created outside Flyway (CI skips this part).
DO $do$
BEGIN
  IF to_regclass('marketing.contacts') IS NOT NULL THEN
    EXECUTE $q$
INSERT INTO lead_intake (business_id, channel, source, traffic_source, phone_number, email, name, detail, external_id,
                         square_customer_id, outcome, marked, status, legacy, created_at)
SELECT DISTINCT ON (c.business_id, right(regexp_replace(c.phone_number, '\D', '', 'g'), 10))
       c.business_id, 'Form',
       CASE
         WHEN lower(COALESCE(c.original_traffic_source, c.marketing_traffic_source, '')) LIKE 'google ads%' THEN 'Google Ads'
         WHEN lower(COALESCE(c.original_traffic_source, c.marketing_traffic_source, '')) LIKE 'meta ads%' THEN 'Meta Ads'
         WHEN lower(COALESCE(c.original_traffic_source, c.marketing_traffic_source, '')) LIKE '%instagram%' THEN 'Instagram'
         WHEN lower(COALESCE(c.original_traffic_source, c.marketing_traffic_source, '')) LIKE '%facebook%' THEN 'Facebook'
         WHEN lower(COALESCE(c.original_traffic_source, c.marketing_traffic_source, '')) LIKE '%google%' THEN 'Google organic'
         WHEN lower(COALESCE(c.original_traffic_source, c.marketing_traffic_source, '')) ~ 'bing|yahoo' THEN 'Search organic'
         WHEN lower(COALESCE(c.original_traffic_source, c.marketing_traffic_source, '')) LIKE 'direct%' THEN 'Direct'
         WHEN COALESCE(c.original_traffic_source, c.marketing_traffic_source, '') = '' THEN 'Unknown'
         ELSE 'Other'
       END,
       COALESCE(c.original_traffic_source, c.marketing_traffic_source),
       c.phone_number, NULLIF(c.email_address, ''), NULLIF(c.given_name, ''), 'Website contact from before the CRM',
       'legacy:contact:' || c.id::text, NULL, 'MATCHED', FALSE, 'OUTDATED', TRUE, c.created_at
FROM marketing.contacts c
WHERE c.business_id IN (1, 2)
  AND length(regexp_replace(COALESCE(c.phone_number, ''), '\D', '', 'g')) >= 10
  AND NOT EXISTS (SELECT 1 FROM lead_intake l WHERE l.business_id = c.business_id
                  AND length(regexp_replace(COALESCE(l.phone_number, ''), '\D', '', 'g')) >= 10
                  AND right(regexp_replace(l.phone_number, '\D', '', 'g'), 10) = right(regexp_replace(c.phone_number, '\D', '', 'g'), 10))
  AND NOT EXISTS (SELECT 1 FROM square_customer sc WHERE sc.business_id = c.business_id AND c.square_customer_id IS NOT NULL
                  AND sc.square_customer_id = c.square_customer_id)
ORDER BY c.business_id, right(regexp_replace(c.phone_number, '\D', '', 'g'), 10), c.created_at
ON CONFLICT DO NOTHING
    $q$;
  END IF;
END
$do$;

CREATE INDEX lead_intake_status_idx ON lead_intake (business_id, status);
