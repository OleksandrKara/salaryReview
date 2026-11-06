-- Daily Telegram digests about leads: 08:05 PT "what to process", 19:50 PT "what was done today".
-- One row per business, kind and day keeps them from ever being sent twice and records skipped (empty) days.
CREATE TABLE lead_digest_send (
    business_id BIGINT      NOT NULL,
    kind        TEXT        NOT NULL,
    day         DATE        NOT NULL,
    status      TEXT        NOT NULL,
    attempts    INT         NOT NULL DEFAULT 0,
    text        TEXT,
    sent_at     TIMESTAMPTZ,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (business_id, kind, day)
);

INSERT INTO sms_automation (business_id, automation_key, enabled, updated_by)
SELECT b.id, 'lead_daily_digest', true, 'migration V190'
FROM business b WHERE b.id IN (1, 2)
ON CONFLICT DO NOTHING;
