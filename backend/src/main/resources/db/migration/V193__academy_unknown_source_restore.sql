-- V192 defaulted Academy leads with no data to Direct. Unknown source stays Unknown (managers can set it by hand).
-- A Direct that came from website contacts is a real value and is kept.
DO $do$
BEGIN
  IF to_regclass('marketing.contacts') IS NOT NULL THEN
    EXECUTE $q$
UPDATE lead_intake l SET source = 'Unknown', traffic_source = NULL
WHERE l.channel = 'Academy' AND l.source = 'Direct' AND l.traffic_source = 'Direct' AND NOT l.source_manual
  AND NOT EXISTS (SELECT 1 FROM marketing.contacts c WHERE c.business_id = l.business_id
      AND (c.phone_number = l.phone_number OR (l.email IS NOT NULL AND lower(c.email_address) = lower(l.email)))
      AND COALESCE(c.original_traffic_source, c.marketing_traffic_source, '') <> '')
    $q$;
  ELSE
    UPDATE lead_intake SET source = 'Unknown', traffic_source = NULL
    WHERE channel = 'Academy' AND source = 'Direct' AND traffic_source = 'Direct' AND NOT source_manual;
  END IF;
END
$do$;
