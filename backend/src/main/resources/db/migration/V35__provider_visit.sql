-- Customer-visit ledger for provider retention analytics. One row per (customer, provider, day) a
-- customer was served — populated from the month aggregator's attributed services (daily accrual +
-- a one-time backfill), the same "collect what Square doesn't expose" pattern as revenue_snapshot.
-- New/returning, retention cohorts, and trend are derived from this by query; only the raw visits are
-- stored (so a deeper backfill self-corrects classification). Anonymous services (no Square customer)
-- are not recorded here. provider_ref is the aggregator's stable provider id; provider_name is a
-- denormalized display label (latest ingest wins). rebooked_same_day captures whether the customer
-- created a future booking on the visit day (Square booking created_at).
CREATE TABLE provider_visit (
    id                 BIGSERIAL    PRIMARY KEY,
    customer_id        VARCHAR(64)  NOT NULL,
    provider_ref       VARCHAR(64)  NOT NULL,
    provider_name      VARCHAR(255),
    service_date       DATE         NOT NULL,
    rebooked_same_day  BOOLEAN      NOT NULL DEFAULT false,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (customer_id, provider_ref, service_date)
);

CREATE INDEX idx_provider_visit_provider_date ON provider_visit (provider_ref, service_date);
CREATE INDEX idx_provider_visit_customer_date ON provider_visit (customer_id, service_date);
