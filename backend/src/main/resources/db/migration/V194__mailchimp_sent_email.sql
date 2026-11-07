-- One row per single-recipient Mailchimp email the app sends, so the CRM can show any email a person received.
CREATE TABLE mailchimp_sent_email (
    id             BIGSERIAL PRIMARY KEY,
    business_id    BIGINT NOT NULL,
    email          TEXT NOT NULL,
    subject        TEXT,
    campaign_title TEXT,
    campaign_id    TEXT NOT NULL UNIQUE,
    sent_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX mailchimp_sent_email_email_idx ON mailchimp_sent_email (business_id, lower(email));
