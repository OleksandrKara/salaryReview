-- Instant reply to the website's untouched "I have a question" text (owner 2026-11-22).
-- New automations ship disabled (sms-automations-hub D8); the owner switches it on from the
-- automations page after reading the wording.
INSERT INTO sms_automation (business_id, automation_key, enabled, updated_by)
SELECT b.id, 'first_contact_autoreply', false, 'migration V230'
FROM business b
WHERE b.id IN (1, 2)
ON CONFLICT DO NOTHING;
