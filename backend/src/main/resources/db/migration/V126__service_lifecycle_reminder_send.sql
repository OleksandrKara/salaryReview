-- Idempotency ledger for lifecycle-reminder automations triggered off a specific past service
-- (e.g. touchup_reminder today, an eventual annual color-booster reminder later) — one row per
-- (business, automation, customer, the specific procedure date that triggered it), so a customer
-- who has the same kind of procedure again in the future is correctly reconsidered, but never
-- re-processed for the same procedure. Generic across automation_key/role on purpose: the shape is
-- identical for "N days after an initial procedure" and "N days after the last qualifying visit,
-- with a cooldown," so a second automation reuses this table rather than getting its own copy.
CREATE TABLE service_lifecycle_reminder_send (
    id                    BIGSERIAL    PRIMARY KEY,
    business_id           BIGINT       NOT NULL,
    automation_key        VARCHAR(64)  NOT NULL,
    square_customer_id    VARCHAR(64)  NOT NULL,
    trigger_service_date  DATE         NOT NULL,
    phone_number          VARCHAR(32),
    customer_name         VARCHAR(255),
    state                 VARCHAR(32)  NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (business_id, automation_key, square_customer_id, trigger_service_date)
);

CREATE INDEX idx_service_lifecycle_reminder_send_lookup
    ON service_lifecycle_reminder_send (business_id, automation_key, square_customer_id, trigger_service_date);
