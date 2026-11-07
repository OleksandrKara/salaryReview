-- Manager follow-up state for leads captured in lead_intake: status, callback reminder, notes.
ALTER TABLE lead_intake ADD COLUMN status TEXT NOT NULL DEFAULT 'NEW';
ALTER TABLE lead_intake ADD COLUMN callback_at TIMESTAMPTZ;
ALTER TABLE lead_intake ADD COLUMN callback_reminded BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE lead_intake ADD COLUMN notes TEXT;
ALTER TABLE lead_intake ADD COLUMN updated_by TEXT;
ALTER TABLE lead_intake ADD COLUMN updated_at TIMESTAMPTZ;
CREATE INDEX lead_intake_callback_idx ON lead_intake (callback_at) WHERE callback_at IS NOT NULL AND NOT callback_reminded;
