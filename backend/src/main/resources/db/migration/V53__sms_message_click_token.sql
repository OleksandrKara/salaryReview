-- Opaque, non-sequential token for the checkout-review-request automation's short links
-- (/r/{token}) — replaces the raw sequential sms_message.id in the URL, which looked like a
-- counter/tracking-link artifact rather than a normal business link (see
-- openspec/changes/sms-automations-hub design.md D6).
ALTER TABLE sms_message ADD COLUMN click_token TEXT;

CREATE UNIQUE INDEX idx_sms_message_click_token ON sms_message (click_token) WHERE click_token IS NOT NULL;
