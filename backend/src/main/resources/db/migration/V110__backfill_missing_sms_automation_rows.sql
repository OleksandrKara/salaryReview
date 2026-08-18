-- Found live 2026-08-18 while auditing "do disabled automations actually stay off": business 2
-- (AK PMU) has zero sms_automation rows for any of the 6 known automation keys. Combined with
-- SmsAutomationService.isEnabled's old fail-OPEN default for a missing row (also fixed in this
-- change), this meant every automation was effectively already enabled for business 2 — masked
-- so far only by Twilio not being configured for it yet, not by anything actually correct.
--
-- Idempotent and generic: backfills any (business, automation_key) pair missing a row, for any
-- business — a no-op for business 1 (every key already has a row there) and harmless if run
-- again. New businesses created going forward get these rows explicitly at creation time (see
-- BusinessProvisioningService#create); this migration only covers businesses that already existed
-- before that code shipped.
INSERT INTO sms_automation (business_id, automation_key, enabled)
SELECT b.id, k.automation_key, false
FROM business b
CROSS JOIN (VALUES ('four_hand_request'), ('checkout_review_request'), ('lead_follow_up'),
                    ('same_day_rebooking_discount'), ('lapsed_customer_winback'), ('repeat_customer_winback'))
    AS k(automation_key)
ON CONFLICT (business_id, automation_key) DO NOTHING;
