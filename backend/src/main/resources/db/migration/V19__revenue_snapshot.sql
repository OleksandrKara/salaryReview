-- Daily revenue snapshots: capture month-to-date revenue + upcoming-booking pipeline state once a
-- day, so the forecaster can later compare past projections against actual month-end revenue and
-- learn the bias of "MTD + upcoming = projected". Square doesn't expose historical pipeline state,
-- so we have to start collecting it ourselves.
CREATE TABLE revenue_snapshot (
    id                BIGSERIAL PRIMARY KEY,
    snapshot_date     DATE NOT NULL UNIQUE,
    mtd_revenue       NUMERIC(10,2) NOT NULL,
    mtd_card          NUMERIC(10,2) NOT NULL,
    mtd_cash          NUMERIC(10,2) NOT NULL,
    mtd_services      INT NOT NULL,
    upcoming_count    INT NOT NULL,
    upcoming_gross    NUMERIC(10,2) NOT NULL,
    -- Filled in by the monthly job after the snapshot's month closes; null until then.
    month_end_actual  NUMERIC(10,2),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
