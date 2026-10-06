-- Consultation follow-up (owner request 2026-10-06): a PMU client who had a consultation and left
-- "to think" without booking a procedure gets at most four touches over ~45 days, from the artist
-- they spoke with: day 2 SMS thank-you, day 5 email (how it works, payment plans), day 21 SMS
-- check-in with real openings, day 45 last-chance $75 OFF (7 days). One row per consultation
-- booking; doubles as idempotency marker and per-step outcome log. stop_reason ends the sequence
-- (the client booked, replied, opted out, or staff tapped "don't message her").
CREATE TABLE consultation_follow_up (
    id                     BIGSERIAL   PRIMARY KEY,
    business_id            BIGINT      NOT NULL REFERENCES business(id),
    square_booking_id      TEXT        NOT NULL,
    square_customer_id     TEXT        NOT NULL,
    team_member_id         TEXT,
    artist_name            TEXT,
    visit_kind             TEXT,
    consultation_start_at  TIMESTAMPTZ NOT NULL,
    customer_name          TEXT,
    phone_number           TEXT,
    staff_alert_sent_at    TIMESTAMPTZ,
    thanks_sms_state       TEXT,
    info_email_state       TEXT,
    checkin_sms_state      TEXT,
    offer_state            TEXT,
    offer_expires_at       TIMESTAMPTZ,
    offer_extended_at      TIMESTAMPTZ,
    stop_reason            TEXT CHECK (stop_reason IN ('BOOKED', 'REPLIED', 'STAFF', 'NOT_ELIGIBLE', 'CANCELLED')),
    stopped_at             TIMESTAMPTZ,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (business_id, square_booking_id)
);

CREATE INDEX idx_consultation_follow_up_active
    ON consultation_follow_up (business_id, stop_reason, consultation_start_at);

-- No sms_automation row: SmsAutomationService#isEnabled fails closed, so nothing runs until the
-- owner turns consultation_follow_up on.
