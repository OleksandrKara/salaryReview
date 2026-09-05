-- Links a checkout-review-request flow back to the SmsMessage row logged for its own "ask"
-- send (SmsReplyFlowScheduler#sendOne) — needed so CheckoutReviewEmailFallbackScheduler can hang
-- its winback_email_send row off a real sms_message.id, reusing that table's existing shape
-- (and its email metrics/Mailchimp-activity-sync plumbing) rather than inventing a parallel one.
-- Nullable: rows created before this migration have none, and the new scheduler simply skips
-- any flow it's null for (see that scheduler's own doc).
ALTER TABLE sms_reply_flow ADD COLUMN ask_sms_message_id BIGINT REFERENCES sms_message(id);
