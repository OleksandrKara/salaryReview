-- Per-person documents (contract, license, NDA, etc.) for service providers and managers, each
-- with a required expiration date so the owner knows when a fresh copy needs to be requested.
-- The actual file is stored in Postgres (bytea) -- no S3/blob storage exists elsewhere in this
-- app, and volumes here are small. Exactly one of provider_id/app_user_id is set: providers are
-- the existing `providers` table identity; managers have no separate entity (MANAGER is just an
-- app_user role), so they're referenced by app_user.id directly.
--
-- A renewal is a new row (never an update to the old one) -- same "correction is a new row, kept
-- for auditability" convention as ad_spend_entries/SOP versions -- so history (what expired when,
-- what replaced it) is never lost.
CREATE TABLE staff_documents (
    id              BIGSERIAL PRIMARY KEY,
    provider_id     BIGINT REFERENCES providers(id) ON DELETE CASCADE,
    app_user_id     BIGINT REFERENCES app_user(id) ON DELETE CASCADE,
    document_type   TEXT NOT NULL,
    label           TEXT,
    file_name       TEXT NOT NULL,
    content_type    TEXT NOT NULL,
    file_data       BYTEA NOT NULL,
    expiration_date DATE NOT NULL,
    created_by      TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT staff_documents_one_owner CHECK (
        (provider_id IS NOT NULL AND app_user_id IS NULL) OR
        (provider_id IS NULL AND app_user_id IS NOT NULL)
    )
);

CREATE INDEX idx_staff_documents_provider ON staff_documents (provider_id);
CREATE INDEX idx_staff_documents_app_user ON staff_documents (app_user_id);
