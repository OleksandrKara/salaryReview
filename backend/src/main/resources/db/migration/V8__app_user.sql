-- Phase 2: real multi-user accounts and roles, replacing the single shared owner login.
-- OWNER   — super admin: config, all reports, tier grants, user management.
-- MANAGER — all reports + tier grants; no user management.
-- PROVIDER— read-only view of their own settlement (provider_id links the account to its payout row)
--           plus approve / request-correction.
-- The first OWNER is seeded at startup from APP_OWNER_USERNAME/APP_OWNER_PASSWORD (OwnerBootstrap),
-- so password_hash is left to the application (bcrypt) rather than the migration.
CREATE TABLE app_user (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL CHECK (role IN ('OWNER', 'MANAGER', 'PROVIDER')),
    provider_id   BIGINT       REFERENCES providers(id) ON DELETE SET NULL,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- A provider account must point at a provider; owner/manager accounts must not.
    CONSTRAINT app_user_provider_link CHECK (
        (role = 'PROVIDER' AND provider_id IS NOT NULL)
        OR (role <> 'PROVIDER' AND provider_id IS NULL)
    )
);

-- At most one account per provider person.
CREATE UNIQUE INDEX app_user_provider_id_uq ON app_user (provider_id) WHERE provider_id IS NOT NULL;
