-- One row per Telegram automation delivery attempt, so the Automations tab can show real sent / last fired /
-- failed numbers for every staff Telegram alert. label is the kind of message inside one automation
-- (e.g. SHIFT_START), direction is nails / pmu / academy (empty when the alert is not split by direction).
CREATE TABLE telegram_automation_log (
    id             BIGSERIAL   PRIMARY KEY,
    business_id    BIGINT      NOT NULL,
    automation_key TEXT        NOT NULL,
    label          TEXT        NOT NULL DEFAULT '',
    direction      TEXT        NOT NULL DEFAULT '',
    ok             BOOLEAN     NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_telegram_automation_log ON telegram_automation_log (business_id, automation_key, created_at DESC);

-- Keep the history the lead digests already have.
INSERT INTO telegram_automation_log (business_id, automation_key, label, direction, ok, created_at)
SELECT business_id, 'lead_daily_digest', CASE kind WHEN 'MORNING' THEN 'SHIFT_START' ELSE 'SHIFT_END' END,
       CASE WHEN business_id = 2 THEN 'pmu' ELSE 'nails' END, true, sent_at
FROM lead_digest_send WHERE status = 'SENT' AND sent_at IS NOT NULL;
