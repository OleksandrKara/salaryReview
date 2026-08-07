-- Lapsed-customer win-back automation: one row per customer, ever — see
-- openspec/changes/lapsed-customer-winback-automation design.md D4. Unlike
-- same_day_rebooking_send, this table isn't fed by a durable "trigger" row; the scheduler computes
-- promo_expires_at itself at send time (see design.md D10), so there's no AWAITING_SEND state here.
CREATE TABLE lapsed_customer_winback_send (
    id                 BIGSERIAL   PRIMARY KEY,
    square_customer_id TEXT        NOT NULL,
    phone_number       TEXT,
    customer_name      TEXT,
    visit_date         DATE        NOT NULL,
    -- Only meaningful for state = SENT — the $5 coupon deadline for that send. Null for every
    -- SKIPPED_* state, since no coupon link was ever generated for those.
    promo_expires_at   TIMESTAMPTZ,
    state              TEXT        NOT NULL CHECK (state IN
        ('SENT', 'SKIPPED_BOOKED', 'SKIPPED_DISABLED', 'SKIPPED_NEGATIVE_FEEDBACK', 'SKIPPED_UNRESOLVED')),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One-shot per customer, ever — not per-visit (see design.md D4's rationale).
CREATE UNIQUE INDEX lapsed_customer_winback_send_customer_idx ON lapsed_customer_winback_send (square_customer_id);

-- Ships disabled — see design.md D11, same rule as every automation so far.
INSERT INTO sms_automation (automation_key, enabled) VALUES ('lapsed_customer_winback', false);
