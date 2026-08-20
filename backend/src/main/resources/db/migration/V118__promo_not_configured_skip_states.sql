-- New skip state for the same-day-rebooking/lapsed-winback/repeat-winback schedulers: a business
-- with the automation enabled but no business_promo_config row yet (Square Customer Group/
-- Discount/Pricing Rule never created) must not send a coupon SMS whose link would 404 — see
-- PromoConfigService and the schedulers' own "no promo terms configured" check.
--
-- same_day_rebooking_send's state check was also missing SKIPPED_NEGATIVE_FEEDBACK even though
-- SameDayRebookingScheduler has used SameDayRebookingSend.STATE_SKIPPED_NEGATIVE_FEEDBACK since
-- that automation shipped — a pre-existing gap (the other two send tables' checks already have
-- it), fixed here since this migration already has to touch the same constraint.
ALTER TABLE same_day_rebooking_send DROP CONSTRAINT same_day_rebooking_send_state_check;
ALTER TABLE same_day_rebooking_send ADD CONSTRAINT same_day_rebooking_send_state_check
    CHECK (state IN ('AWAITING_SEND', 'SENT', 'SKIPPED_BOOKED', 'SKIPPED_NO_CONSENT', 'SKIPPED_EXPIRED',
                      'SKIPPED_DISABLED', 'SKIPPED_NEGATIVE_FEEDBACK', 'SKIPPED_PROMO_NOT_CONFIGURED'));

ALTER TABLE lapsed_customer_winback_send DROP CONSTRAINT lapsed_customer_winback_send_state_check;
ALTER TABLE lapsed_customer_winback_send ADD CONSTRAINT lapsed_customer_winback_send_state_check
    CHECK (state IN ('SENT', 'SKIPPED_BOOKED', 'SKIPPED_DISABLED', 'SKIPPED_NEGATIVE_FEEDBACK',
                      'SKIPPED_UNRESOLVED', 'SKIPPED_PROMO_NOT_CONFIGURED'));

ALTER TABLE repeat_customer_winback_send DROP CONSTRAINT repeat_customer_winback_send_state_check;
ALTER TABLE repeat_customer_winback_send ADD CONSTRAINT repeat_customer_winback_send_state_check
    CHECK (state IN ('SENT', 'SKIPPED_BOOKED', 'SKIPPED_DISABLED', 'SKIPPED_NEGATIVE_FEEDBACK',
                      'SKIPPED_UNRESOLVED', 'SKIPPED_BLOCKED', 'SKIPPED_PROMO_NOT_CONFIGURED'));
