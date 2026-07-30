-- Lead-follow-up automation: durable idempotency marker + outcome log per marketing.contacts row.
-- See openspec/changes/lead-followup-and-manager-inbox design.md D3. No FK to marketing.contacts —
-- that table is owned/migrated by the separate salonLandings service, same reasoning as
-- marketing_contact_square_link (V43) never referencing it directly either.
CREATE TABLE lead_followup_send (
    id           BIGSERIAL   PRIMARY KEY,
    contact_id   UUID        NOT NULL,
    phone_number TEXT        NOT NULL,
    state        TEXT        NOT NULL CHECK (state IN ('SENT', 'SKIPPED_BOOKED', 'SKIPPED_DISABLED')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX lead_followup_send_contact_idx ON lead_followup_send (contact_id);

-- Ships disabled — see design.md D5, same rule as every automation so far.
INSERT INTO sms_automation (automation_key, enabled) VALUES ('lead_follow_up', false);
