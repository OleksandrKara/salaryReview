-- 2026-10-12. (1) SKIPPED_TEXTED_US for all three steps: the lead texted the studio in the last
-- 24h, so a "started booking online, can I help?" nudge would land on top of a live conversation
-- (owner report: a client asked about makeup by SMS, got a staff reply, then the nudge a minute
-- later). (2) Fixes a latent mismatch: LeadFollowUpScheduler already writes SKIPPED_RECENTLY_SENT
-- to email_followup_state and sms_followup_state, but V154's constraints never allowed it, so
-- that guard failed on save every poll instead of recording the skip.
ALTER TABLE lead_followup_send DROP CONSTRAINT lead_followup_send_state_check;
ALTER TABLE lead_followup_send ADD CONSTRAINT lead_followup_send_state_check
    CHECK (state IN ('SENT', 'SKIPPED_BOOKED', 'SKIPPED_DISABLED', 'SKIPPED_RECENTLY_SENT', 'SKIPPED_TEXTED_US'));
ALTER TABLE lead_followup_send DROP CONSTRAINT lead_followup_send_email_followup_state_check;
ALTER TABLE lead_followup_send ADD CONSTRAINT lead_followup_send_email_followup_state_check
    CHECK (email_followup_state IN ('SENT', 'SKIPPED_BOOKED', 'SKIPPED_DISABLED', 'SKIPPED_NO_EMAIL',
                                     'SKIPPED_NOT_CONFIGURED', 'SKIPPED_NO_TEMPLATE', 'SEND_FAILED',
                                     'SKIPPED_RECENTLY_SENT', 'SKIPPED_TEXTED_US'));
ALTER TABLE lead_followup_send DROP CONSTRAINT lead_followup_send_sms_followup_state_check;
ALTER TABLE lead_followup_send ADD CONSTRAINT lead_followup_send_sms_followup_state_check
    CHECK (sms_followup_state IN ('SENT', 'SKIPPED_BOOKED', 'SKIPPED_DISABLED', 'SKIPPED_RECENTLY_SENT', 'SKIPPED_TEXTED_US'));
