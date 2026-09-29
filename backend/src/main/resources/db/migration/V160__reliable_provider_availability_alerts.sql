-- Observe the new detector before enabling Telegram. Preserve existing automation enablement.
ALTER TABLE provider_schedule_closure_alert_config
    ADD COLUMN minimum_loss_window_minutes INT NOT NULL DEFAULT 240 CHECK (minimum_loss_window_minutes BETWEEN 60 AND 1440),
    ADD COLUMN observation_only BOOLEAN NOT NULL DEFAULT true;

CREATE TABLE provider_availability_observation (
    id BIGSERIAL PRIMARY KEY,
    business_id BIGINT NOT NULL REFERENCES business(id),
    team_member_id TEXT NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('SUCCESS', 'ERROR')),
    reason TEXT NOT NULL,
    payload JSONB,
    UNIQUE (business_id, id)
);
CREATE INDEX provider_availability_observation_business_time_idx
    ON provider_availability_observation (business_id, captured_at);

CREATE TABLE provider_availability_state (
    business_id BIGINT NOT NULL REFERENCES business(id),
    team_member_id TEXT NOT NULL,
    observation_id BIGINT,
    PRIMARY KEY (business_id, team_member_id),
    FOREIGN KEY (business_id, observation_id) REFERENCES provider_availability_observation(business_id, id)
);

CREATE TABLE provider_schedule_change_event (
    id BIGSERIAL PRIMARY KEY,
    business_id BIGINT NOT NULL REFERENCES business(id),
    team_member_id TEXT NOT NULL,
    team_member_name TEXT NOT NULL,
    affected_date DATE NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('PENDING', 'CONFIRMED', 'OBSERVED', 'RESOLVED', 'SUPPRESSED', 'EXPIRED')),
    reason TEXT NOT NULL,
    first_detected_at TIMESTAMPTZ NOT NULL,
    confirmed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    first_start_at TIMESTAMPTZ NOT NULL,
    last_start_at TIMESTAMPTZ NOT NULL,
    start_count INT NOT NULL,
    baseline JSONB NOT NULL,
    latest_observation_id BIGINT,
    UNIQUE (business_id, team_member_id, affected_date),
    UNIQUE (business_id, id),
    FOREIGN KEY (business_id, latest_observation_id) REFERENCES provider_availability_observation(business_id, id)
);
CREATE INDEX provider_schedule_change_event_business_time_idx
    ON provider_schedule_change_event (business_id, updated_at DESC);

ALTER TABLE provider_schedule_closure_alert
    ADD COLUMN event_id BIGINT,
    ADD COLUMN affected_date DATE,
    ADD COLUMN delivery_status TEXT NOT NULL DEFAULT 'LEGACY'
        CHECK (delivery_status IN ('LEGACY', 'PENDING', 'ATTEMPTING', 'SENT', 'FAILED', 'UNKNOWN', 'SUPPRESSED')),
    ADD COLUMN confirmed_at TIMESTAMPTZ,
    ADD COLUMN delivered_at TIMESTAMPTZ,
    ADD COLUMN attempted_at TIMESTAMPTZ,
    ADD COLUMN attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN telegram_message_id BIGINT,
    ADD COLUMN notice_threshold_hours INT,
    ADD COLUMN minimum_loss_window_minutes INT,
    ADD COLUMN timezone TEXT,
    ADD CONSTRAINT provider_schedule_closure_alert_event_fk
        FOREIGN KEY (business_id, event_id) REFERENCES provider_schedule_change_event (business_id, id);
CREATE UNIQUE INDEX provider_schedule_closure_alert_daily_idx
    ON provider_schedule_closure_alert(business_id, team_member_id, affected_date)
    WHERE affected_date IS NOT NULL;
CREATE INDEX provider_schedule_closure_alert_pending_idx
    ON provider_schedule_closure_alert(business_id, delivery_status, confirmed_at);
