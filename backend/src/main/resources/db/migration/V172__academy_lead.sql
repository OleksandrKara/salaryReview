-- PMU Academy course requests from pmu-annakara.com/academy (2026-10-13). No online payment: the
-- academy never sold a course online, so a request is a lead that staff follow up by hand. Kept in
-- its own table (not marketing.contacts) so studio follow-ups never text a would-be student, and so
-- the academy's own SMS/email funnel can be built on these rows later. Consent flags are separate
-- because promo SMS needs explicit marketing consent.
CREATE TABLE academy_lead (
    id                 BIGSERIAL PRIMARY KEY,
    business_id        BIGINT       NOT NULL REFERENCES business (id),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    name               TEXT         NOT NULL,
    phone              TEXT,
    email              TEXT,
    course_slug        TEXT,
    course_name        TEXT,
    message            TEXT,
    sms_consent        BOOLEAN      NOT NULL DEFAULT FALSE,
    email_consent      BOOLEAN      NOT NULL DEFAULT FALSE,
    source_page_url    TEXT,
    utm_source         TEXT,
    utm_medium         TEXT,
    utm_campaign       TEXT,
    gclid              TEXT,
    telegram_sent      BOOLEAN      NOT NULL DEFAULT FALSE,
    hubspot_contact_id TEXT,
    hubspot_error      TEXT
);

CREATE INDEX academy_lead_business_created_idx ON academy_lead (business_id, created_at DESC);
