CREATE INDEX IF NOT EXISTS lead_intake_email_idx ON lead_intake (business_id, lower(email)) WHERE email IS NOT NULL AND email <> '';
