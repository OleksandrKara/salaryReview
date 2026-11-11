-- Offline "SMS Sent" conversions to upload to Google Ads: one row per website Text tap that led to a
-- real inbound text and carried a Google click id. Kept in public (marketing schema is not in the CI DB).
CREATE TABLE google_ads_sms_conversions (
    id               BIGSERIAL PRIMARY KEY,
    business_id      BIGINT       NOT NULL,
    customer_id      TEXT         NOT NULL,
    order_id         TEXT         NOT NULL,
    gclid            TEXT,
    gbraid           TEXT,
    wbraid           TEXT,
    conversion_time  TIMESTAMPTZ  NOT NULL,
    -- where the text came from, so each conversion can be read without joining events
    traffic_source   TEXT,
    utm_source       TEXT,
    utm_medium       TEXT,
    utm_campaign     TEXT,
    referrer         TEXT,
    landing_page_slug TEXT,
    landing_path     TEXT,
    page_path        TEXT,
    link_source      TEXT,
    topic            TEXT,
    status           TEXT         NOT NULL DEFAULT 'PENDING',
    attempts         INT          NOT NULL DEFAULT 0,
    last_error       TEXT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_attempt_at  TIMESTAMPTZ,
    uploaded_at      TIMESTAMPTZ,
    CONSTRAINT google_ads_sms_conv_status CHECK (status IN ('PENDING', 'UPLOADED', 'FAILED')),
    CONSTRAINT google_ads_sms_conv_click CHECK (gclid IS NOT NULL OR gbraid IS NOT NULL OR wbraid IS NOT NULL),
    CONSTRAINT google_ads_sms_conv_order UNIQUE (customer_id, order_id)
);
CREATE UNIQUE INDEX google_ads_sms_conv_gclid ON google_ads_sms_conversions (customer_id, gclid) WHERE gclid IS NOT NULL;
CREATE INDEX google_ads_sms_conv_pending ON google_ads_sms_conversions (status) WHERE status = 'PENDING';
