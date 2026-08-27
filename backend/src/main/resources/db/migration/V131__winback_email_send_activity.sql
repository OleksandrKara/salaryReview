-- Open/click telemetry for the win-back email fallback, pulled from Mailchimp's per-recipient
-- email-activity report (each campaign has exactly one recipient — see MailchimpClient's class
-- doc — so that report's timestamps are unambiguously this customer's own open/click, not an
-- aggregate). Synced periodically by MailchimpActivitySyncScheduler, not fetched live on every
-- dashboard load.
ALTER TABLE winback_email_send ADD COLUMN opened_at TIMESTAMPTZ;
ALTER TABLE winback_email_send ADD COLUMN email_clicked_at TIMESTAMPTZ;
