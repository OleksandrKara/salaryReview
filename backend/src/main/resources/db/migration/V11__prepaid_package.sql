-- A prepaid package: a customer paid one Square invoice in advance for several services with one
-- provider, to be drawn down over later visits. amount/total_services are the balance + proof; the
-- provider is paid per draw-down on the service's catalog menu price (see prepaid_redemption).
CREATE TABLE prepaid_package (
    id             BIGSERIAL PRIMARY KEY,
    customer_id    VARCHAR(64),                 -- Square customer id, when known
    customer_name  VARCHAR(255) NOT NULL,
    provider_id    BIGINT NOT NULL REFERENCES providers(id) ON DELETE CASCADE,
    paid_date      DATE NOT NULL,
    amount         NUMERIC(10,2) NOT NULL,
    total_services INT NOT NULL CHECK (total_services > 0),
    invoice_ref    VARCHAR(128),                -- optional Square invoice # for reference
    created_by     VARCHAR(100),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
