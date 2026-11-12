-- Telegram notices already sent for the "needs a reply" queue: one row per (business, kind, ref) so a chat is never alerted twice.
CREATE TABLE needs_reply_notice (
    id          BIGSERIAL PRIMARY KEY,
    business_id BIGINT      NOT NULL,
    kind        TEXT        NOT NULL,
    ref_key     TEXT        NOT NULL,
    sent_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (business_id, kind, ref_key)
);

-- Moment the escalation went live: waits that began earlier are shown in the CRM queue but never alerted one by one.
CREATE TABLE needs_reply_escalation_start (
    id         INT         PRIMARY KEY CHECK (id = 1),
    started_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO needs_reply_escalation_start (id) VALUES (1);
