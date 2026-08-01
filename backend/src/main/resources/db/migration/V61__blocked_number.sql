-- Manager conversation view (/admin/messages): "Block number" — see TwilioSmsService, which is
-- the single choke point every outbound SMS (automated or manual) already goes through, so
-- checking this table there blocks everything at once with no per-automation special-casing.
CREATE TABLE blocked_number (
    phone_number TEXT NOT NULL PRIMARY KEY,
    blocked_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
