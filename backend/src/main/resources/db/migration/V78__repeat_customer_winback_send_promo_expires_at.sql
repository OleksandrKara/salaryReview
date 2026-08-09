-- repeat_customer_winback now reuses the already-live WINBACK5 ($5 off, $99 minimum) promo — see
-- RepeatCustomerWinbackScheduler. Mirrors lapsed_customer_winback_send.promo_expires_at (same
-- column, same nullable-until-SENT convention) purely for audit/reporting parity between the two
-- otherwise near-identical automations; nothing about applying the discount itself depends on it
-- (that's entirely carried by sms_message.link_target's own WINBACK:<epoch> value).
ALTER TABLE repeat_customer_winback_send ADD COLUMN promo_expires_at TIMESTAMPTZ;
