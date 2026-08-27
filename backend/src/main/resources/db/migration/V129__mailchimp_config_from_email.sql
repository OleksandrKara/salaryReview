-- from_email was missing from V128 — without it, campaign creation silently falls back to
-- whatever the Mailchimp account's own list-level campaign_defaults.from_email is, which can be a
-- completely unrelated business's domain if the same Mailchimp account is shared (confirmed live:
-- this account's list default is anna@pmu-annakara.com). Mailchimp requires both from_name and
-- from_email on every campaign send.
ALTER TABLE mailchimp_config ADD COLUMN from_email TEXT;
