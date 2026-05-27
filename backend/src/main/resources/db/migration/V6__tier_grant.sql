-- A manual 50/50 grant: an owner/manager awards the tier to a provider for a calendar month even
-- when the counted services fall short of the threshold ("close enough"). One grant per
-- provider/month; removing the row reverts to the automatic count-based decision.
CREATE TABLE tier_grant (
    id          BIGSERIAL PRIMARY KEY,
    provider_id BIGINT NOT NULL REFERENCES providers(id) ON DELETE CASCADE,
    year        INT NOT NULL,
    month       INT NOT NULL CHECK (month BETWEEN 1 AND 12),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (provider_id, year, month)
);
