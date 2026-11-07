-- Manual Academy tag on a person: TRUE forces it on, FALSE forces it off, NULL follows the Academy channel.
ALTER TABLE lead_intake ADD COLUMN IF NOT EXISTS academy_override BOOLEAN;
