-- Stores the exact rendered HTML sent, so the manager conversation view's inline email preview
-- (under the original SMS bubble it followed up on) doesn't depend on a live Mailchimp API call —
-- available even if the Mailchimp campaign is later archived/deleted on their side.
ALTER TABLE winback_email_send ADD COLUMN content_html TEXT;
