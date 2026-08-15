-- Phase 3.1 (multi-tenant Square support) — one row per business's Square account. Mirrors the
-- existing single-token model (SquareProperties: environment/access-token/location-id) exactly,
-- just per-business instead of per-process; see design.md D5. access_token_encrypted is AES-GCM
-- ciphertext (SquareCredentialCipher), never plaintext. merchant_id is nullable — Square doesn't
-- require it upfront and today's SquareClient.Location silently discards it (D5's own note).

CREATE TABLE square_connection (
    id                     BIGSERIAL PRIMARY KEY,
    business_id            BIGINT NOT NULL UNIQUE REFERENCES business(id),
    environment            VARCHAR(20) NOT NULL,
    access_token_encrypted TEXT NOT NULL,
    location_id            VARCHAR(64) NOT NULL,
    merchant_id            VARCHAR(64),
    connected_by_user_id   BIGINT NOT NULL REFERENCES app_user(id),
    connected_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_sync_at           TIMESTAMPTZ,
    CONSTRAINT square_connection_environment_check
        CHECK (environment IN ('SANDBOX', 'PRODUCTION'))
);
