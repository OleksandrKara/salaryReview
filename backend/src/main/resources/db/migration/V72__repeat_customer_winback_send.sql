-- Repeat-customer win-back automation: for customers with 2+ completed visits who are 40+ days
-- overdue by their own last completed visit — the "next" retention automation after
-- lapsed_customer_winback (which only covers exactly-one-visit customers). Unlike that table, a
-- customer here is NOT one-shot: they can lapse, come back, and lapse again, so this is a
-- recurring send subject to a 60-day cooldown per customer (see repeat_customer_winback_send_customer_idx),
-- not a permanent per-customer marker.
CREATE TABLE repeat_customer_winback_send (
    id                    BIGSERIAL   PRIMARY KEY,
    square_customer_id    TEXT        NOT NULL,
    phone_number          TEXT,
    last_visit_date       DATE        NOT NULL,
    days_since_last_visit INTEGER     NOT NULL,
    total_visit_count     INTEGER     NOT NULL,
    last_provider         TEXT,
    previous_provider     TEXT,
    provider_changed      BOOLEAN,
    rebooked_same_day     BOOLEAN,
    -- Which body was used, e.g. "default" or "previous_provider" — only set for state = SENT.
    message_variant       TEXT,
    state                 TEXT        NOT NULL CHECK (state IN
        ('SENT', 'SKIPPED_BOOKED', 'SKIPPED_DISABLED', 'SKIPPED_NEGATIVE_FEEDBACK',
         'SKIPPED_UNRESOLVED', 'SKIPPED_BLOCKED')),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Backs the eligibility query's own 60-day cooldown check (only a SENT row within the window
-- excludes a customer) — not unique, since the same customer can legitimately appear again once
-- they lapse a second time.
CREATE INDEX repeat_customer_winback_send_customer_idx ON repeat_customer_winback_send (square_customer_id, created_at);

-- Ships disabled — same rule as every automation so far (see V54, V55, V68).
INSERT INTO sms_automation (automation_key, enabled) VALUES ('repeat_customer_winback', false);
