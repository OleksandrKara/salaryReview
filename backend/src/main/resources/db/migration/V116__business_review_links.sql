ALTER TABLE business
    ADD COLUMN google_review_url TEXT,
    ADD COLUMN feedback_form_url TEXT;

-- Backfill Business A's existing, previously hardcoded values (see CheckoutReviewLinks) so its
-- checkout_review_request automation keeps working unchanged. Every other business starts null —
-- CheckoutReviewTriggerService treats that as "review links not configured yet" and skips
-- creating the flow, rather than sending a customer a link to the wrong salon's Google listing.
UPDATE business
SET google_review_url = 'https://g.page/r/CY0ZQsqUPmkaEBM/review',
    feedback_form_url = 'https://forms.gle/53FQHGUWJUhkuRaW7'
WHERE short_code = 'akluxnails';
