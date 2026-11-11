ALTER TABLE lead_intake ADD COLUMN IF NOT EXISTS attribution jsonb;
CREATE INDEX IF NOT EXISTS idx_lead_intake_attr_gclid ON lead_intake ((attribution->>'gclid')) WHERE attribution->>'gclid' IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_lead_intake_attr_campaign ON lead_intake ((attribution->>'utm_campaign')) WHERE attribution->>'utm_campaign' IS NOT NULL;

-- Idempotent backfill from whatever UTMs old payloads carried (Academy camelCase, Ringostat snake_case).
UPDATE lead_intake SET attribution = jsonb_strip_nulls(jsonb_build_object(
        'utm_source', COALESCE(payload::jsonb->>'utm_source', payload::jsonb->>'utmSource'),
        'utm_medium', COALESCE(payload::jsonb->>'utm_medium', payload::jsonb->>'utmMedium'),
        'utm_campaign', COALESCE(payload::jsonb->>'utm_campaign', payload::jsonb->>'utmCampaign'),
        'utm_term', payload::jsonb->>'utm_term',
        'utm_content', payload::jsonb->>'utm_content',
        'gclid', payload::jsonb->>'gclid',
        'traffic_source', traffic_source))
WHERE attribution IS NULL AND payload IS NOT NULL AND left(payload, 1) = '{'
  AND (payload::jsonb ? 'utm_source' OR payload::jsonb ? 'utmSource' OR payload::jsonb ? 'utm_campaign' OR payload::jsonb ? 'utmCampaign' OR payload::jsonb ? 'gclid');
