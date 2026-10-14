-- Email open/click tracking for the academy follow-up funnel (automations page metrics,
-- 2026-10-14): the Mailchimp campaign behind each email step, synced every 30 minutes.
ALTER TABLE academy_lead_touch ADD COLUMN campaign_id TEXT;
ALTER TABLE academy_lead_touch ADD COLUMN opened_at TIMESTAMPTZ;
ALTER TABLE academy_lead_touch ADD COLUMN clicked_at TIMESTAMPTZ;
CREATE INDEX academy_lead_touch_created_at_idx ON academy_lead_touch (created_at);

