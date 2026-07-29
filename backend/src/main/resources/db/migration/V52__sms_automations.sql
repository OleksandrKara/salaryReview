-- Owner-facing SMS automation registry + full sent/received activity log + the delayed-send state
-- machine backing the checkout-review-request automation. See openspec/changes/sms-automations-hub.

-- enabled defaults to false at the schema level, not just per-seed-row: any future automation this
-- table gains inherits "never live until an owner explicitly turns it on" even if its own migration
-- forgets to say so (see design.md D8).
CREATE TABLE sms_automation (
    automation_key TEXT        PRIMARY KEY,
    enabled        BOOLEAN     NOT NULL DEFAULT false,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by     TEXT
);

-- four_hand_request is already live in production today — this migration makes it visible, it
-- doesn't turn anything on. checkout_review_request is brand new and ships disabled; the owner
-- flips it on from the hub only after testing it end to end.
INSERT INTO sms_automation (automation_key, enabled) VALUES
    ('four_hand_request', true),
    ('checkout_review_request', false);

-- Every outbound send attempt (sent or not) and every inbound message, regardless of whether it
-- belongs to an automation. read_at is only ever set on INBOUND rows (see design.md D9) — an
-- unmatched inbound text still needs to visibly demand attention, not just get filed away.
CREATE TABLE sms_message (
    id                 BIGSERIAL   PRIMARY KEY,
    direction          TEXT        NOT NULL CHECK (direction IN ('OUTBOUND', 'INBOUND')),
    automation_key     TEXT        REFERENCES sms_automation(automation_key),
    phone_number       TEXT        NOT NULL,
    template_key       TEXT,
    body               TEXT        NOT NULL,
    twilio_message_sid TEXT,
    status             TEXT        NOT NULL,
    reason             TEXT,
    link_target        TEXT,
    clicked_at         TIMESTAMPTZ,
    read_at            TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX sms_message_phone_number_idx ON sms_message (phone_number);
CREATE INDEX sms_message_automation_key_idx ON sms_message (automation_key);
-- Backs the hub's unread-count query cheaply — small, self-maintaining partial index.
CREATE INDEX sms_message_unread_idx ON sms_message (direction, read_at) WHERE direction = 'INBOUND' AND read_at IS NULL;

-- One row per in-flight checkout-review conversation (see design.md D3).
CREATE TABLE sms_reply_flow (
    id                BIGSERIAL   PRIMARY KEY,
    automation_key    TEXT        NOT NULL REFERENCES sms_automation(automation_key),
    phone_number      TEXT        NOT NULL,
    customer_name     TEXT,
    state             TEXT        NOT NULL CHECK (state IN ('AWAITING_SEND', 'AWAITING_REPLY', 'COMPLETED', 'EXPIRED')),
    square_payment_id TEXT,
    send_due_at       TIMESTAMPTZ NOT NULL,
    reply_expires_at  TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX sms_reply_flow_due_idx ON sms_reply_flow (state, send_due_at);
CREATE INDEX sms_reply_flow_phone_state_idx ON sms_reply_flow (phone_number, state);
-- Square may redeliver the same webhook event; this makes enqueue-on-qualifying-payment idempotent.
CREATE UNIQUE INDEX sms_reply_flow_payment_idx ON sms_reply_flow (square_payment_id) WHERE square_payment_id IS NOT NULL;
