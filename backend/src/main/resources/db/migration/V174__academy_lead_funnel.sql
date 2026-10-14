-- Academy lead follow-up funnel (owner 2026-10-14). kind separates course requests from
-- "Subscribe now" sign-ups (subscribers get no follow-ups); status stops the funnel once staff
-- have talked to the person (Telegram "stop follow-ups" link) or the lead texts back.
ALTER TABLE academy_lead ADD COLUMN kind TEXT NOT NULL DEFAULT 'course_request';
ALTER TABLE academy_lead ADD COLUMN status TEXT NOT NULL DEFAULT 'new';
ALTER TABLE academy_lead ADD COLUMN stopped_at TIMESTAMPTZ;
UPDATE academy_lead SET kind = 'subscribe' WHERE message = 'Subscribed to academy news' AND phone IS NULL;

-- One row per lead and funnel step; the unique key makes every step send at most once.
CREATE TABLE academy_lead_touch (
    id         BIGSERIAL PRIMARY KEY,
    lead_id    BIGINT      NOT NULL REFERENCES academy_lead (id) ON DELETE CASCADE,
    step       TEXT        NOT NULL,
    channel    TEXT        NOT NULL,
    state      TEXT        NOT NULL,
    detail     TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (lead_id, step)
);

-- Owner approved the texts and asked to launch (2026-10-14), so on for the PMU business (id 2).
INSERT INTO sms_automation (business_id, automation_key, enabled, updated_by)
SELECT 2, 'academy_lead_funnel', true, 'migration V174'
WHERE EXISTS (SELECT 1 FROM business WHERE id = 2)
ON CONFLICT DO NOTHING;
