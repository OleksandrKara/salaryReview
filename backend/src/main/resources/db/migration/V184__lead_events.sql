-- CRM history for a lead (the earliest lead_intake row of a contact): manual notes, calls, agreements, system events.
CREATE TABLE lead_events (
    id          BIGSERIAL PRIMARY KEY,
    lead_id     BIGINT      NOT NULL REFERENCES lead_intake (id) ON DELETE CASCADE,
    business_id BIGINT      NOT NULL,
    type        TEXT        NOT NULL,
    text        TEXT,
    created_by  TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX lead_events_lead_idx ON lead_events (lead_id, created_at);
