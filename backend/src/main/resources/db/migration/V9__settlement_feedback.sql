-- Phase 2: a provider's response to their own month settlement. Either they APPROVE it, or they
-- request a correction (CHANGES_REQUESTED) with a comment the owner/manager can read on the report.
-- One row per provider/month; re-submitting updates it.
CREATE TABLE settlement_feedback (
    id          BIGSERIAL PRIMARY KEY,
    provider_id BIGINT NOT NULL REFERENCES providers(id) ON DELETE CASCADE,
    year        INT NOT NULL,
    month       INT NOT NULL CHECK (month BETWEEN 1 AND 12),
    status      VARCHAR(20) NOT NULL CHECK (status IN ('APPROVED', 'CHANGES_REQUESTED')),
    comment     TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (provider_id, year, month)
);
