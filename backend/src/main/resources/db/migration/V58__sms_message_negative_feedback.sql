-- Set on the INBOUND row itself when a customer's reply to the checkout-review-request
-- automation contains a low (1-4) digit rating — permanently excludes them from the
-- same-day-rebooking win-back nudge, and flags the conversation in the manager view.
-- See negative-feedback-tracking design.
ALTER TABLE sms_message ADD COLUMN negative_feedback_at TIMESTAMPTZ;
