-- Per-provider review tracking (owner-facing /owner/reviews dashboard): which technician a
-- checkout-review-request flow was about, and what numeric rating (if any) the customer's reply
-- actually contained. Neither was ever persisted before — the technician was resolved fresh at
-- send time only to render the greeting text and then thrown away, and the rating was only ever
-- checked as a "contains '5'"/"contains 1-4" boolean, never stored as a real 1-5 value.
ALTER TABLE sms_reply_flow ADD COLUMN provider_id BIGINT REFERENCES providers(id);
ALTER TABLE sms_message ADD COLUMN reply_flow_id BIGINT REFERENCES sms_reply_flow(id);
ALTER TABLE sms_message ADD COLUMN rating INTEGER;

CREATE INDEX idx_sms_reply_flow_provider ON sms_reply_flow(business_id, provider_id) WHERE provider_id IS NOT NULL;
CREATE INDEX idx_sms_message_reply_flow ON sms_message(reply_flow_id) WHERE reply_flow_id IS NOT NULL;
