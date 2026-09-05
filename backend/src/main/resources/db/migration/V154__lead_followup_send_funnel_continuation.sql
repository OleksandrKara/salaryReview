-- Extends lead_follow_up from a single SMS nudge into a 3-step funnel (owner request 2026-09-05):
-- step 1 (existing) SMS at ~2 min, step 2 (new) email at ~24h if still unbooked and not replied,
-- step 3 (new) a final plain SMS at ~72h. Both new steps are tracked on the SAME touch row step 1
-- already created (not a new table) — a lead who resubmits already starts a fresh row via the
-- existing contactId/contactUpdatedAt idempotency key, so the whole 3-step sequence naturally
-- restarts for a genuinely new touch, same as step 1 alone already worked.
ALTER TABLE lead_followup_send ADD COLUMN email_followup_state TEXT
    CHECK (email_followup_state IN ('SENT', 'SKIPPED_BOOKED', 'SKIPPED_DISABLED', 'SKIPPED_NO_EMAIL',
                                     'SKIPPED_NOT_CONFIGURED', 'SKIPPED_NO_TEMPLATE', 'SEND_FAILED'));
ALTER TABLE lead_followup_send ADD COLUMN sms_followup_state TEXT
    CHECK (sms_followup_state IN ('SENT', 'SKIPPED_BOOKED', 'SKIPPED_DISABLED'));

-- A real structural gap found while adding the above: this table never carried business_id at
-- all (V54 predates every other business this app now has), so LeadFollowUpScheduler's own step 1
-- resolves it from the outer per-business loop it's already inside, but a row saved here has no
-- way to recover which business it belongs to afterward — needed now that steps 2/3 run as their
-- own independent poll, well after that original loop context is gone. In practice every existing
-- row is business 1 (lead_follow_up has only ever been enabled for akluxnails — see sms_automation),
-- so backfilling to that business is exact, not a guess.
ALTER TABLE lead_followup_send ADD COLUMN business_id BIGINT REFERENCES business(id);
UPDATE lead_followup_send SET business_id = (SELECT id FROM business WHERE short_code = 'akluxnails');
ALTER TABLE lead_followup_send ALTER COLUMN business_id SET NOT NULL;
