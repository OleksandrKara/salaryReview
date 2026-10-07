-- Single-recipient Mailchimp campaigns whose send hit "recipients not ready" through every inline
-- retry (found 2026-10-07: ~2-4% of business 1's automation emails were lost this way, almost all
-- to a member upserted seconds earlier). The campaign itself is complete; MailchimpDeferredSendScheduler
-- retries the send every few minutes for up to about an hour instead of the scheduler thread
-- blocking on it.
CREATE TABLE mailchimp_deferred_send (
    id               BIGSERIAL   PRIMARY KEY,
    business_id      BIGINT      NOT NULL REFERENCES business(id),
    campaign_id      TEXT        NOT NULL UNIQUE,
    campaign_title   TEXT,
    state            TEXT        NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'SENT', 'FAILED')),
    attempts         INT         NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ NOT NULL,
    last_error       TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at      TIMESTAMPTZ
);

CREATE INDEX idx_mailchimp_deferred_send_due ON mailchimp_deferred_send (state, next_attempt_at);
