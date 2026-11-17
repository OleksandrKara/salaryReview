-- Intake form consent (owner request 2026-11-17): the client ticks an explicit line saying the team of
-- the legal entity can see her answers (also in Square). The exact text she accepted is stored with
-- the signature. legal_name is the legal entity shown in that line; null falls back to business.name.
ALTER TABLE business ADD COLUMN IF NOT EXISTS legal_name TEXT;
ALTER TABLE business ADD COLUMN IF NOT EXISTS privacy_policy_url TEXT;
ALTER TABLE intake_form ADD COLUMN IF NOT EXISTS consent_text TEXT;
ALTER TABLE intake_form ADD COLUMN IF NOT EXISTS consent_version TEXT;

UPDATE business SET legal_name = 'Anna Kara''s Brow Studio LLC dba AK.LUX.NAILS',
                    privacy_policy_url = 'https://akluxnails.com/privacy-policy'
WHERE id = 1 AND legal_name IS NULL;
