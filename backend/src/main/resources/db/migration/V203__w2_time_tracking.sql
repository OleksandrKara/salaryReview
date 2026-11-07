-- W2 time tracking: worker type, approvers, rate history, Square timecard mirror, reviews, audit, reminders, meal waivers.

ALTER TABLE providers ADD COLUMN IF NOT EXISTS worker_type TEXT NOT NULL DEFAULT '1099'
    CHECK (worker_type IN ('W2', '1099'));
ALTER TABLE providers ADD COLUMN IF NOT EXISTS show_commission_comparison_to_provider BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE provider_employment (
    id                          BIGSERIAL PRIMARY KEY,
    business_id                 BIGINT NOT NULL,
    provider_id                 BIGINT NOT NULL REFERENCES providers(id),
    hourly_rate_cents           INT NOT NULL CHECK (hourly_rate_cents > 0),
    effective_from              DATE NOT NULL,
    effective_to                DATE,
    mobile_phone                TEXT,
    payroll_automation_enabled  BOOLEAN NOT NULL DEFAULT FALSE,
    alerts_enabled              BOOLEAN NOT NULL DEFAULT TRUE,
    sms_enabled                 BOOLEAN NOT NULL DEFAULT FALSE,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (business_id, provider_id, effective_from)
);
CREATE INDEX idx_provider_employment_provider ON provider_employment (business_id, provider_id, effective_from DESC);

CREATE TABLE provider_time_approver (
    id           BIGSERIAL PRIMARY KEY,
    business_id  BIGINT NOT NULL,
    provider_id  BIGINT NOT NULL REFERENCES providers(id),
    app_user_id  BIGINT NOT NULL REFERENCES app_user(id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (business_id, provider_id, app_user_id)
);

CREATE TABLE square_timecard_mirror (
    id                 BIGSERIAL PRIMARY KEY,
    business_id        BIGINT NOT NULL,
    square_timecard_id TEXT NOT NULL,
    provider_id        BIGINT NOT NULL REFERENCES providers(id),
    work_date          DATE NOT NULL,
    start_at           TIMESTAMPTZ NOT NULL,
    end_at             TIMESTAMPTZ,
    timezone           TEXT NOT NULL,
    hourly_rate_cents  INT,
    breaks_json        TEXT NOT NULL DEFAULT '[]',
    status             TEXT NOT NULL,
    square_version     INT,
    deleted            BOOLEAN NOT NULL DEFAULT FALSE,
    synced_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (business_id, square_timecard_id)
);
CREATE INDEX idx_timecard_mirror_day ON square_timecard_mirror (business_id, provider_id, work_date);

CREATE TABLE timecard_review (
    id                    BIGSERIAL PRIMARY KEY,
    business_id           BIGINT NOT NULL,
    provider_id           BIGINT NOT NULL REFERENCES providers(id),
    work_date             DATE NOT NULL,
    mode                  TEXT NOT NULL CHECK (mode IN ('DRAFT', 'EXTERNAL', 'SELF')),
    status                TEXT NOT NULL CHECK (status IN
        ('PROPOSED', 'WORKER_CONFIRMED', 'NEEDS_REVIEW', 'APPROVED', 'CONFIRMED_PENDING_WRITE')),
    proposed_json         TEXT,
    worker_confirmed_at   TIMESTAMPTZ,
    approved_at           TIMESTAMPTZ,
    approved_by_user_id   BIGINT REFERENCES app_user(id),
    meal_waiver           BOOLEAN NOT NULL DEFAULT FALSE,
    rest_confirmed        BOOLEAN NOT NULL DEFAULT FALSE,
    note                  TEXT,
    square_timecard_id    TEXT,
    square_version        INT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (business_id, provider_id, work_date)
);

CREATE TABLE timecard_audit (
    id           BIGSERIAL PRIMARY KEY,
    business_id  BIGINT NOT NULL,
    review_id    BIGINT NOT NULL REFERENCES timecard_review(id),
    actor        TEXT NOT NULL,
    action       TEXT NOT NULL,
    before_json  TEXT,
    after_json   TEXT,
    at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_timecard_audit_review ON timecard_audit (business_id, review_id);

CREATE TABLE timecard_reminder (
    id           BIGSERIAL PRIMARY KEY,
    business_id  BIGINT NOT NULL,
    review_id    BIGINT NOT NULL REFERENCES timecard_review(id),
    kind         TEXT NOT NULL CHECK (kind IN ('INITIAL', 'R16', 'ESCALATION')),
    twilio_sid   TEXT,
    sent_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (business_id, review_id, kind)
);

CREATE TABLE meal_waiver_agreement (
    id            BIGSERIAL PRIMARY KEY,
    business_id   BIGINT NOT NULL,
    provider_id   BIGINT NOT NULL REFERENCES providers(id),
    text_version  TEXT NOT NULL,
    text_body     TEXT NOT NULL,
    signed_name   TEXT NOT NULL,
    signed_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    signed_via    TEXT NOT NULL,
    signed_ip     TEXT,
    revoked_at    TIMESTAMPTZ,
    revoked_note  TEXT
);
CREATE INDEX idx_meal_waiver_provider ON meal_waiver_agreement (business_id, provider_id, signed_at DESC);
