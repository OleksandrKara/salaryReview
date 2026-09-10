-- Provider-schedule-closure Telegram alert (owner request 2026-09-10): flags when a provider
-- blocks off part of their own Square calendar with less than a day's notice, so the manager on
-- shift can check whether it was actually arranged in advance. Registered as a normal automation
-- (same enable/disable table every SMS automation already uses) so it shows up on the
-- /owner/automations hub and can be toggled the same way — its "channel" is Telegram rather than
-- SMS, see SmsAutomationRegistry.

INSERT INTO sms_automation (business_id, automation_key, enabled)
SELECT b.id, 'provider_schedule_closure_alert', false
FROM business b
ON CONFLICT (business_id, automation_key) DO NOTHING;

-- Owner asked for this enabled only for business 1 (AK.LUX.NAILS) to start.
UPDATE sms_automation SET enabled = true
WHERE automation_key = 'provider_schedule_closure_alert'
  AND business_id = (SELECT id FROM business WHERE short_code = 'akluxnails');

-- The last poll's known-available slot-start times per (business, provider) — diffed against on
-- the next poll to find newly-missing slots. Fully replaced every poll (see
-- ProviderScheduleClosureAlertScheduler), not an append-only history.
CREATE TABLE provider_availability_snapshot (
    id             BIGSERIAL   PRIMARY KEY,
    business_id    BIGINT      NOT NULL REFERENCES business(id),
    team_member_id TEXT        NOT NULL,
    slot_start_at  TIMESTAMPTZ NOT NULL,
    captured_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX provider_availability_snapshot_slot_idx
    ON provider_availability_snapshot (business_id, team_member_id, slot_start_at);

-- One row per Telegram alert actually sent (grouping every slot a single poll found newly closed
-- for one provider into one row/one message, not one row per slot) — an audit trail, and backs
-- this automation's "sent" count on the /owner/automations hub.
CREATE TABLE provider_schedule_closure_alert (
    id               BIGSERIAL   PRIMARY KEY,
    business_id      BIGINT      NOT NULL REFERENCES business(id),
    team_member_id   TEXT        NOT NULL,
    team_member_name TEXT,
    slot_count       INT         NOT NULL,
    earliest_slot_at TIMESTAMPTZ NOT NULL,
    latest_slot_at   TIMESTAMPTZ NOT NULL,
    sent_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX provider_schedule_closure_alert_business_idx ON provider_schedule_closure_alert (business_id, sent_at);
