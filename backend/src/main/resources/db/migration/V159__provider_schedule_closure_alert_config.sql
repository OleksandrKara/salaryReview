CREATE TABLE provider_schedule_closure_alert_config (
    id                     BIGSERIAL   PRIMARY KEY,
    business_id            BIGINT      NOT NULL UNIQUE REFERENCES business(id),
    notice_threshold_hours INT         NOT NULL DEFAULT 24 CHECK (notice_threshold_hours > 0),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by             TEXT
);
