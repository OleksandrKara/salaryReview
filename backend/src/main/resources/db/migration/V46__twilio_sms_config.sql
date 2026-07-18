-- Single-row runtime config for outbound SMS (Twilio), owned by this app so mani/akluxnails-home
-- never hold the credentials — they call POST /api/internal/notifications/sms/send instead. See
-- openspec/changes/sms-automation-platform for the design.
--
-- Seeded NULL deliberately — never put a live secret in a git-committed migration. The owner sets
-- the real Account SID/API Key/Secret/from-number once via the owner UI.
CREATE TABLE twilio_sms_config (
    id                BOOLEAN     PRIMARY KEY DEFAULT true CHECK (id),  -- enforces exactly one row
    account_sid       TEXT,          -- Twilio Account SID (e.g. "AC...") — URL path segment only
    api_key           TEXT,          -- Twilio API Key SID (e.g. "SK...") — Basic Auth username
    api_secret        TEXT,          -- Twilio API Key Secret — Basic Auth password
    from_phone_number TEXT,          -- the Twilio number messages are sent from
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by        TEXT
);

INSERT INTO twilio_sms_config (id, account_sid, api_key, api_secret, from_phone_number, updated_by)
VALUES (true, NULL, NULL, NULL, NULL, NULL);
