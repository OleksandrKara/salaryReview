-- One-time: website contacts that never booked become NEW leads so managers can follow up.
-- Businesses 1 and 2 only (the ones with lead intake enabled). Idempotent through external_id.
INSERT INTO lead_intake (business_id, channel, source, traffic_source, phone_number, email, name, detail,
                         external_id, square_customer_id, outcome, marked, created_at)
SELECT c.business_id, 'Form',
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
       c.phone_number, NULLIF(c.email_address, ''), c.given_name, 'Imported from website contacts (no booking)',
       'contact:' || c.id::text, c.square_customer_id, 'MATCHED', FALSE, c.created_at
FROM marketing.contacts c
WHERE c.business_id IN (1, 2)
  AND c.square_booking_id IS NULL
  AND (c.square_customer_id IS NULL OR NOT EXISTS (
        SELECT 1 FROM square_booking b WHERE b.business_id = c.business_id AND b.square_customer_id = c.square_customer_id))
  AND NOT EXISTS (
        SELECT 1 FROM square_customer sc JOIN square_booking b ON b.business_id = sc.business_id AND b.square_customer_id = sc.square_customer_id
        WHERE sc.business_id = c.business_id AND sc.phone_number IS NOT NULL
          AND right(regexp_replace(sc.phone_number, '\D', '', 'g'), 10) = right(regexp_replace(c.phone_number, '\D', '', 'g'), 10))
  AND NOT EXISTS (
        SELECT 1 FROM lead_intake l WHERE l.business_id = c.business_id AND l.phone_number IS NOT NULL
          AND right(regexp_replace(l.phone_number, '\D', '', 'g'), 10) = right(regexp_replace(c.phone_number, '\D', '', 'g'), 10))
ON CONFLICT DO NOTHING;
