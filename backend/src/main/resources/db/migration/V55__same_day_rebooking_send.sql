-- Same-day-rebooking-discount automation: durable delayed-send state (per Square payment) and
-- the per-customer Square auto-discount customer-group membership expiry sweep.
-- See openspec/changes/same-day-rebooking-discount design.md D1/D7.

CREATE TABLE same_day_rebooking_send (
    id                 BIGSERIAL   PRIMARY KEY,
    phone_number       TEXT        NOT NULL,
    customer_name      TEXT,
    square_customer_id TEXT        NOT NULL,
    square_payment_id  TEXT        NOT NULL,
    send_due_at        TIMESTAMPTZ NOT NULL,
    promo_expires_at   TIMESTAMPTZ NOT NULL,
    state              TEXT        NOT NULL CHECK (state IN
        ('AWAITING_SEND', 'SENT', 'SKIPPED_BOOKED', 'SKIPPED_NO_CONSENT',
         'SKIPPED_EXPIRED', 'SKIPPED_DISABLED')),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX same_day_rebooking_send_due_idx ON same_day_rebooking_send (state, send_due_at);
-- Square may redeliver the same webhook event; this makes enqueue-on-qualifying-payment idempotent,
-- same pattern as sms_reply_flow_payment_idx (V52).
CREATE UNIQUE INDEX same_day_rebooking_send_payment_idx ON same_day_rebooking_send (square_payment_id);

-- One row per customer currently enrolled in the live Square "same-day rebooking" auto-discount
-- customer group, so the group-expiry sweep knows who to remove and when (see design.md D7). Not
-- tied 1:1 to same_day_rebooking_send — a booking under an active promo enrolls a customer here
-- regardless of whether the SMS itself was ever sent (e.g. a manually-shared link).
CREATE TABLE same_day_rebooking_group_membership (
    id                 BIGSERIAL   PRIMARY KEY,
    square_customer_id TEXT        NOT NULL,
    expires_at         TIMESTAMPTZ NOT NULL,
    removed_at         TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX same_day_rebooking_group_membership_expiry_idx
    ON same_day_rebooking_group_membership (expires_at) WHERE removed_at IS NULL;

-- Ships disabled — see design.md D10, same rule as every automation so far.
INSERT INTO sms_automation (automation_key, enabled) VALUES ('same_day_rebooking_discount', false);
