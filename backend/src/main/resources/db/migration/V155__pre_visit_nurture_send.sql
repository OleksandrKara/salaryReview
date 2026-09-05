-- Pre-visit nurture email sequence (owner request 2026-09-05): a customer who just booked gets a
-- warm welcome email shortly after, and (if their appointment is far enough out) a day-before
-- reminder — goal is fewer cancellations/no-shows via familiarity with the studio before the
-- visit, not a booking-conversion ask (they've already booked). One row per real Square booking,
-- doubling as idempotency marker and outcome log per step, same shape as lead_followup_send's own
-- welcome/reminder-state columns.
CREATE TABLE pre_visit_nurture_send (
    id                    BIGSERIAL   PRIMARY KEY,
    business_id           BIGINT      NOT NULL REFERENCES business(id),
    square_booking_id     TEXT        NOT NULL,
    square_customer_id    TEXT,
    appointment_start_at  TIMESTAMPTZ NOT NULL,
    welcome_state         TEXT        CHECK (welcome_state IN ('SENT', 'SKIPPED_DISABLED', 'SKIPPED_NO_EMAIL',
                                                                 'SKIPPED_NOT_CONFIGURED', 'SKIPPED_NO_TEMPLATE',
                                                                 'SEND_FAILED')),
    reminder_state        TEXT        CHECK (reminder_state IN ('SENT', 'SKIPPED_DISABLED', 'SKIPPED_NO_EMAIL',
                                                                  'SKIPPED_NOT_CONFIGURED', 'SKIPPED_NO_TEMPLATE',
                                                                  'SKIPPED_CANCELLED', 'SEND_FAILED')),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (business_id, square_booking_id)
);

CREATE INDEX idx_pre_visit_nurture_send_reminder_window
    ON pre_visit_nurture_send (business_id, welcome_state, reminder_state, appointment_start_at);

-- No sms_automation row inserted — SmsAutomationService#isEnabled already fails closed (disabled)
-- when no row exists for a (business, key) pair at all, same as every other automation ships;
-- a row is only ever created once an owner explicitly turns this on.
