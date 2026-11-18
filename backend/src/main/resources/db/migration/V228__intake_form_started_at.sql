-- First time the client typed anything into the form (opened_at is only the page load), for the
-- sent / opened / started / signed funnel.
ALTER TABLE intake_form ADD COLUMN started_at TIMESTAMPTZ;
