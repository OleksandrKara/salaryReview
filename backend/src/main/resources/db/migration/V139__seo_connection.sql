-- seo-monitoring-dashboard Phase 1 (design.md D1) — one encrypted row per business holding
-- Search Console + GA4 + PageSpeed Insights credentials. Mirrors square_connection's shape
-- exactly, but with its own cipher/master-key (SeoCredentialCipher, SEO_CREDENTIALS_MASTER_KEY) —
-- rotating a Square incident's key must never force re-encrypting every business's Google
-- credentials, and vice versa.
CREATE TABLE seo_connection (
    id                                BIGSERIAL PRIMARY KEY,
    business_id                       BIGINT NOT NULL UNIQUE REFERENCES business(id),
    gsc_service_account_json_encrypted TEXT NOT NULL,
    ga4_property_id                  TEXT NOT NULL,
    ga4_measurement_id               TEXT NOT NULL,
    pagespeed_api_key_encrypted       TEXT NOT NULL,
    connected_by_user_id             BIGINT NOT NULL,
    connected_at                     TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_sync_at                     TIMESTAMPTZ,
    last_sync_error                  TEXT
);
