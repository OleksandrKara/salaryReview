-- Adds LeadFollowUpSend.STATE_SKIPPED_RECENTLY_SENT (see LeadFollowUpScheduler's 2026-09-05
-- duplicate-text fix) to the state check constraint from V54 — without this, saving that new
-- state value 500s on the check constraint the moment the guard it backs actually fires.
ALTER TABLE lead_followup_send DROP CONSTRAINT lead_followup_send_state_check;
ALTER TABLE lead_followup_send ADD CONSTRAINT lead_followup_send_state_check
    CHECK (state IN ('SENT', 'SKIPPED_BOOKED', 'SKIPPED_DISABLED', 'SKIPPED_RECENTLY_SENT'));
