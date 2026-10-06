-- Online booking health checks (owner request 2026-10-06): the PMU booking popup once broke after
-- a change in Square ("Unable to load service catalog") and nobody knew. BookingHealthCheckScheduler
-- probes the booking API every 15 minutes; this keeps per-check state so staff get one Telegram
-- message when a check starts failing and one when it works again.
CREATE TABLE booking_health_state (
    business_id     BIGINT      NOT NULL REFERENCES business(id),
    check_key       TEXT        NOT NULL,
    fail_count      INT         NOT NULL DEFAULT 0,
    alerted         BOOLEAN     NOT NULL DEFAULT FALSE,
    failing_since   TIMESTAMPTZ,
    last_error      TEXT,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (business_id, check_key)
);
