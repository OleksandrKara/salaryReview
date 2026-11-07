-- Every lead (call, SMS, form, booking) that reaches the business, with the Square customer it was
-- tied to and how: found an existing profile (MATCHED), created one (CREATED), or failed (FAILED,
-- retried by the reconciler). external_id (e.g. a call id) makes webhook retries harmless.
CREATE TABLE lead_intake (
    id                 BIGSERIAL PRIMARY KEY,
    business_id        BIGINT      NOT NULL,
    channel            TEXT        NOT NULL,
    source             TEXT        NOT NULL,
    traffic_source     TEXT,
    phone_number       TEXT,
    email              TEXT,
    name               TEXT,
    detail             TEXT,
    external_id        TEXT,
    square_customer_id TEXT,
    outcome            TEXT        NOT NULL,
    marked             BOOLEAN     NOT NULL DEFAULT FALSE,
    error              TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX lead_intake_external_idx ON lead_intake (business_id, channel, external_id) WHERE external_id IS NOT NULL;
CREATE INDEX lead_intake_business_created_idx ON lead_intake (business_id, created_at DESC);
CREATE INDEX lead_intake_customer_idx ON lead_intake (business_id, square_customer_id);
