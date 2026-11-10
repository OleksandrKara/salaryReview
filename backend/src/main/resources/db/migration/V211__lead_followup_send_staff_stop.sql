-- Staff can stop the website lead nudge from the lead card ("Stop funnel"): every undecided step becomes SKIPPED_STAFF.
ALTER TABLE lead_followup_send DROP CONSTRAINT lead_followup_send_state_check;
ALTER TABLE lead_followup_send ADD CONSTRAINT lead_followup_send_state_check
    CHECK (state IN ('SENT', 'SKIPPED_BOOKED', 'SKIPPED_DISABLED', 'SKIPPED_RECENTLY_SENT', 'SKIPPED_TEXTED_US', 'SKIPPED_STAFF'));
ALTER TABLE lead_followup_send DROP CONSTRAINT lead_followup_send_email_followup_state_check;
ALTER TABLE lead_followup_send ADD CONSTRAINT lead_followup_send_email_followup_state_check
    CHECK (email_followup_state IN ('SENT', 'SKIPPED_BOOKED', 'SKIPPED_DISABLED', 'SKIPPED_NO_EMAIL',
                                     'SKIPPED_NOT_CONFIGURED', 'SKIPPED_NO_TEMPLATE', 'SEND_FAILED',
                                     'SKIPPED_RECENTLY_SENT', 'SKIPPED_TEXTED_US', 'SKIPPED_STAFF'));
ALTER TABLE lead_followup_send DROP CONSTRAINT lead_followup_send_sms_followup_state_check;
ALTER TABLE lead_followup_send ADD CONSTRAINT lead_followup_send_sms_followup_state_check
    CHECK (sms_followup_state IN ('SENT', 'SKIPPED_BOOKED', 'SKIPPED_DISABLED', 'SKIPPED_RECENTLY_SENT', 'SKIPPED_TEXTED_US', 'SKIPPED_STAFF'));
