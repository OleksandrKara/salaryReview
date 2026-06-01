-- A redo: a customer was unhappy and had the service redone by a DIFFERENT provider. The commission
-- moves from the original provider (on the original service's date) to the redo provider (on the redo
-- date) — both can be in the same period or the redo in the next one. amount = the service's price.
CREATE TABLE redo (
    id                   BIGSERIAL PRIMARY KEY,
    original_provider_id BIGINT NOT NULL REFERENCES providers(id),
    redo_provider_id     BIGINT NOT NULL REFERENCES providers(id),
    original_date        DATE NOT NULL,
    redo_date            DATE NOT NULL,
    amount               NUMERIC(10,2) NOT NULL,
    service_name         VARCHAR(255),
    created_by           VARCHAR(100),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
