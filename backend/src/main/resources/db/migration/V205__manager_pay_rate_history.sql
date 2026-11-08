-- Manager hourly rates become effective-dated, so raising a rate from a given date no longer rewrites
-- past months. The old single-row table is left in place (unused) as a rollback safety net.
CREATE TABLE manager_pay_rate_history (
    id             BIGSERIAL PRIMARY KEY,
    user_id        BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    effective_from DATE NOT NULL,
    usd_per_hour   NUMERIC(8,2) NOT NULL,
    updated_by     VARCHAR(100),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_manager_pay_rate_history UNIQUE (user_id, effective_from)
);

-- Existing rates apply to all past dates (epoch start), exactly as before.
INSERT INTO manager_pay_rate_history (user_id, effective_from, usd_per_hour, updated_by, updated_at)
SELECT user_id, DATE '2000-01-01', usd_per_hour, updated_by, updated_at FROM manager_pay_rate;
