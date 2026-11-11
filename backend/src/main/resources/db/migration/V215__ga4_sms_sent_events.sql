-- Server-side GA4 "sms_sent" events: one row per real inbound text that was tied to a website Text tap.
-- Kept so each text is sent to GA4 exactly once (unique per business and tap), retried while GA4 is
-- unreachable, and so every conversion can be read with its traffic source. Public schema: the marketing
-- schema is not in the CI test DB.
CREATE TABLE ga4_sms_sent_events (
    id                BIGSERIAL PRIMARY KEY,
    business_id       BIGINT       NOT NULL,
    order_id          TEXT         NOT NULL,
    client_id         TEXT         NOT NULL,
    client_id_is_ga   BOOLEAN      NOT NULL,
    ga_session_id     TEXT,
    occurred_at       TIMESTAMPTZ  NOT NULL,
    traffic_source    TEXT,
    utm_source        TEXT,
    utm_medium        TEXT,
    utm_campaign      TEXT,
    gclid             TEXT,
    gbraid            TEXT,
    wbraid            TEXT,
    referrer          TEXT,
    landing_page_slug TEXT,
    landing_path      TEXT,
    page_path         TEXT,
    link_source       TEXT,
    topic             TEXT,
    status            TEXT         NOT NULL DEFAULT 'PENDING',
    attempts          INT          NOT NULL DEFAULT 0,
    last_error        TEXT,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_attempt_at   TIMESTAMPTZ,
    sent_at           TIMESTAMPTZ,
    CONSTRAINT ga4_sms_sent_status CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    CONSTRAINT ga4_sms_sent_order UNIQUE (business_id, order_id)
);
CREATE INDEX ga4_sms_sent_pending ON ga4_sms_sent_events (status) WHERE status = 'PENDING';
