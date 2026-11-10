ALTER TABLE sms_message ADD COLUMN telegram_alerted_at TIMESTAMPTZ;
UPDATE sms_message SET telegram_alerted_at = created_at WHERE direction = 'INBOUND';
CREATE INDEX idx_sms_message_inbound_unalerted ON sms_message (created_at)
    WHERE direction = 'INBOUND' AND telegram_alerted_at IS NULL;
