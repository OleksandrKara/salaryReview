-- Emoji reactions on individual sms_message rows — two sources: CUSTOMER (Apple's tapback-over-SMS
-- fallback text, e.g. `Loved "message"`, detected and parsed against the reacted-to outbound
-- message — see SmsReactionService) and STAFF (an internal-only reaction a manager/owner adds from
-- the dashboard, never sent to the customer). One row per (message, source, reactor) — a customer
-- re-tapping a message with a different reaction, or a staff member changing their own reaction,
-- updates this same row rather than accumulating duplicates (see SmsReactionService's upsert).
-- reactor is 'customer' (a fixed sentinel) for CUSTOMER rows, and the staff username for STAFF rows.
CREATE TABLE sms_message_reaction (
    id             BIGSERIAL   PRIMARY KEY,
    sms_message_id BIGINT      NOT NULL REFERENCES sms_message (id) ON DELETE CASCADE,
    emoji          TEXT        NOT NULL,
    source         TEXT        NOT NULL CHECK (source IN ('CUSTOMER', 'STAFF')),
    reactor        TEXT        NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX sms_message_reaction_sms_message_id_idx ON sms_message_reaction (sms_message_id);
CREATE UNIQUE INDEX sms_message_reaction_unique_idx ON sms_message_reaction (sms_message_id, source, reactor);
