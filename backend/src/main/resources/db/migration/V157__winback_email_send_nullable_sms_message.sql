-- Lets a pure-email, no-SMS-leg-at-all campaign (ColorBoosterWinbackOneOffService, 2026-09-05) log
-- into this same table instead of duplicating its whole shape (contentHtml, mailchimp_campaign_id,
-- opened_at/email_clicked_at, and the existing MailchimpActivitySyncScheduler that keeps them
-- fresh) just because it has no sms_message row to point at. Postgres treats each NULL in a UNIQUE
-- column as distinct from every other NULL, so this doesn't weaken the constraint for the SMS-
-- fallback automations that do always set it — only opens the column up for a row that genuinely
-- has none.
ALTER TABLE winback_email_send ALTER COLUMN sms_message_id DROP NOT NULL;
