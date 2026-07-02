-- Manager time tracking: managers log their worked shifts here (replacing a spreadsheet); owners set
-- each manager's hourly rate. Pay = hours worked x rate, grouped into the salon's half-month periods.

-- One hourly rate per manager. Only owners set it (enforced in the API). Null row = not set yet.
CREATE TABLE manager_pay_rate (
    user_id        BIGINT PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
    usd_per_hour   NUMERIC(8,2) NOT NULL,
    updated_by     VARCHAR(100),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One row per worked shift. A row with end_at = NULL is an open shift (currently clocked in).
-- work_date is the salon-local date of the shift, denormalized so half-month grouping needs no tz math.
CREATE TABLE manager_time_entry (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    work_date   DATE NOT NULL,
    start_at    TIMESTAMPTZ NOT NULL,
    end_at      TIMESTAMPTZ,
    note        VARCHAR(255),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_manager_time_entry_user_date ON manager_time_entry (user_id, work_date);
-- At most one open shift per manager.
CREATE UNIQUE INDEX uq_manager_time_entry_open ON manager_time_entry (user_id) WHERE end_at IS NULL;
