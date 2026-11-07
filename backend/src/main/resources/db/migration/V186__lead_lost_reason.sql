-- Why a lead was lost: set by a manager when moving the lead to LOST (reason is mandatory).
ALTER TABLE lead_intake ADD COLUMN lost_reason TEXT;
ALTER TABLE lead_intake ADD COLUMN lost_comment TEXT;
ALTER TABLE lead_intake ADD COLUMN lost_at TIMESTAMPTZ;
CREATE INDEX lead_intake_lost_idx ON lead_intake (business_id, lost_at) WHERE lost_reason IS NOT NULL;
