-- Twilio's delivery-status callback (queued/sent/delivered/undelivered/failed), so the manager
-- conversation view at /admin/messages can surface an outbound message that never actually
-- reached the customer's phone, not just whether our own send attempt to Twilio succeeded (which
-- is all `sms_message.status` has ever tracked) — see delivery-status-tracking design.
ALTER TABLE sms_message ADD COLUMN delivery_status TEXT;
ALTER TABLE sms_message ADD COLUMN delivery_error_code TEXT;
ALTER TABLE sms_message ADD COLUMN delivery_error_message TEXT;
ALTER TABLE sms_message ADD COLUMN delivery_updated_at TIMESTAMPTZ;

CREATE INDEX idx_sms_message_twilio_sid ON sms_message (twilio_message_sid) WHERE twilio_message_sid IS NOT NULL;
