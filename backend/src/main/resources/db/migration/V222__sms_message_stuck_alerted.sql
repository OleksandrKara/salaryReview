-- Set once the stuck-delivery reconciler has looked at (and, if needed, alerted on) an outbound
-- row whose Twilio status callback never arrived, so each row is handled at most once.
ALTER TABLE sms_message ADD COLUMN stuck_alerted_at TIMESTAMPTZ;
