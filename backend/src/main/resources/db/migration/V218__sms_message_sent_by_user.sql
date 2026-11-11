-- Who typed a manual SMS reply (CRM inbox). NULL for automations, system sends and rows written before this column.
ALTER TABLE sms_message ADD COLUMN sent_by_user_id BIGINT;
CREATE INDEX idx_sms_message_biz_created ON sms_message (business_id, created_at);
