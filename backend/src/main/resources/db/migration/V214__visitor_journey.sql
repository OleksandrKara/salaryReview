ALTER TABLE marketing.visits
    ADD COLUMN IF NOT EXISTS landing_url text,
    ADD COLUMN IF NOT EXISTS visit_referrer text,
    ADD COLUMN IF NOT EXISTS is_first_visit boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS gbraid text,
    ADD COLUMN IF NOT EXISTS wbraid text,
    ADD COLUMN IF NOT EXISTS msclkid text;

ALTER TABLE marketing.submissions
    ADD COLUMN IF NOT EXISTS gbraid text,
    ADD COLUMN IF NOT EXISTS wbraid text,
    ADD COLUMN IF NOT EXISTS msclkid text,
    ADD COLUMN IF NOT EXISTS landing_url text,
    ADD COLUMN IF NOT EXISTS page_path text;

ALTER TABLE lead_intake ADD COLUMN IF NOT EXISTS visitor_id uuid;

CREATE INDEX IF NOT EXISTS ix_visits_visitor ON marketing.visits (business_id, visitor_id, occurred_at);
CREATE INDEX IF NOT EXISTS ix_events_session_created ON marketing.events (session_id, created_at);
CREATE INDEX IF NOT EXISTS ix_submissions_visitor ON marketing.submissions (business_id, visitor_id);
CREATE INDEX IF NOT EXISTS ix_lead_intake_visitor ON lead_intake (visitor_id) WHERE visitor_id IS NOT NULL;
