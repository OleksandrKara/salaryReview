-- A manual service credit: an owner/manager credits a provider for a service that Square recorded too
-- messily to auto-attribute (e.g. paid on a card machine as a custom amount, or checked out under the
-- wrong date). It's folded into the settlement exactly like a card service — gross (commission basis),
-- the salon-absorbed discount, and the tip — for the given period. A deliberate, audited exception.
CREATE TABLE manual_credit (
    id           BIGSERIAL PRIMARY KEY,
    provider_id  BIGINT NOT NULL REFERENCES providers(id),
    service_date DATE NOT NULL,
    gross        NUMERIC(10,2) NOT NULL,
    discount     NUMERIC(10,2) NOT NULL DEFAULT 0,
    tip          NUMERIC(10,2) NOT NULL DEFAULT 0,
    service_name VARCHAR(255),
    created_by   VARCHAR(100),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
