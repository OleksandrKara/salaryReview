-- Intake form critical answers (owner request 2026-11-18): a signed form with a flagged answer
-- (fungal/contagious, cuts, epilepsy = CRITICAL; allergies, sensitivity = ATTENTION) alerts the managers'
-- Telegram chat and is marked loudly in Square, on the client note and on her upcoming bookings.
ALTER TABLE intake_form ADD COLUMN IF NOT EXISTS attention_level TEXT;
ALTER TABLE intake_form ADD COLUMN IF NOT EXISTS critical_alert_state TEXT;
ALTER TABLE intake_form ADD COLUMN IF NOT EXISTS critical_alert_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE intake_form ADD COLUMN IF NOT EXISTS critical_alerted_at TIMESTAMPTZ;

-- Which Square bookings already carry the alert text in their seller note, and which text (flag_key).
CREATE TABLE IF NOT EXISTS intake_booking_flag_sync (
    business_id        BIGINT      NOT NULL,
    square_booking_id  TEXT        NOT NULL,
    flag_key           TEXT        NOT NULL,
    attempts           INT         NOT NULL DEFAULT 0,
    synced_at          TIMESTAMPTZ,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (business_id, square_booking_id)
);
