-- History of the Google Ads account(s) (PMU + Academy share one account), pulled nightly through the
-- Google Ads API (read-only service account). Lets the weekly Telegram report and later analysis look
-- back beyond what Ads itself keeps readable, and keeps who-changed-what after Ads' 30-day window.
CREATE TABLE google_ads_daily (
    customer_id    TEXT        NOT NULL,
    business_id    BIGINT      NOT NULL,
    campaign_id    BIGINT      NOT NULL,
    day            DATE        NOT NULL,
    campaign_name  TEXT        NOT NULL,
    campaign_status TEXT,
    channel_type   TEXT,
    cost_micros    BIGINT      NOT NULL DEFAULT 0,
    impressions    BIGINT      NOT NULL DEFAULT 0,
    clicks         BIGINT      NOT NULL DEFAULT 0,
    conversions    NUMERIC(14,2) NOT NULL DEFAULT 0,
    all_conversions NUMERIC(14,2) NOT NULL DEFAULT 0,
    synced_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (customer_id, campaign_id, day)
);
CREATE INDEX google_ads_daily_business_day_idx ON google_ads_daily (business_id, day);

CREATE TABLE google_ads_conversions_daily (
    customer_id      TEXT        NOT NULL,
    business_id      BIGINT      NOT NULL,
    campaign_id      BIGINT      NOT NULL,
    day              DATE        NOT NULL,
    action_name      TEXT        NOT NULL,
    all_conversions  NUMERIC(14,2) NOT NULL DEFAULT 0,
    all_conv_value   NUMERIC(14,2) NOT NULL DEFAULT 0,
    synced_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (customer_id, campaign_id, day, action_name)
);
CREATE INDEX google_ads_conv_business_day_idx ON google_ads_conversions_daily (business_id, day);

CREATE TABLE google_ads_changes (
    id              BIGSERIAL PRIMARY KEY,
    customer_id     TEXT        NOT NULL,
    business_id     BIGINT      NOT NULL,
    changed_at      TIMESTAMPTZ NOT NULL,
    user_email      TEXT,
    client_type     TEXT,
    resource_type   TEXT        NOT NULL,
    resource_name   TEXT        NOT NULL,
    operation       TEXT        NOT NULL,
    campaign_id     BIGINT,
    changed_fields  TEXT,
    UNIQUE (customer_id, changed_at, resource_name, operation)
);
CREATE INDEX google_ads_changes_business_at_idx ON google_ads_changes (business_id, changed_at DESC);
