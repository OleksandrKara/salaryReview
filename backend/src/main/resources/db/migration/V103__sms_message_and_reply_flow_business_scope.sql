-- sms_message and sms_reply_flow predate multi-tenancy and had zero tenant boundary (tasks.md
-- 2.6 — the one gap deliberately left open when twilio_sms_config/telegram_notification_config
-- were scoped, since these two tables needed a real column, not a join). Both are root tables
-- with no existing FK into an already business-scoped table (phone_number is just a string;
-- sms_reply_flow.automation_key FKs into sms_automation, which stays a global registry of
-- automation *types*, not per-business data). In practice every existing row already belongs to
-- Business A — the SmsBusinessScopeFilter stopgap has blocked any other business from ever
-- writing to these paths — so this backfill is a straightforward "everything to Business A".
--
-- sms_message_media and sms_message_reaction (V69/V70) get no migration — both FK into
-- sms_message.id ON DELETE CASCADE, so they're always reached through an already-verified
-- sms_message row; no separate business_id column needed on either.
ALTER TABLE sms_message ADD COLUMN business_id BIGINT;
UPDATE sms_message SET business_id = (SELECT id FROM business WHERE short_code = 'akluxnails');
ALTER TABLE sms_message ALTER COLUMN business_id SET NOT NULL;
ALTER TABLE sms_message ADD CONSTRAINT sms_message_business_id_fkey FOREIGN KEY (business_id) REFERENCES business (id);
CREATE INDEX idx_sms_message_business_id ON sms_message (business_id);

ALTER TABLE sms_reply_flow ADD COLUMN business_id BIGINT;
UPDATE sms_reply_flow SET business_id = (SELECT id FROM business WHERE short_code = 'akluxnails');
ALTER TABLE sms_reply_flow ALTER COLUMN business_id SET NOT NULL;
ALTER TABLE sms_reply_flow ADD CONSTRAINT sms_reply_flow_business_id_fkey FOREIGN KEY (business_id) REFERENCES business (id);
CREATE INDEX idx_sms_reply_flow_business_id ON sms_reply_flow (business_id);
