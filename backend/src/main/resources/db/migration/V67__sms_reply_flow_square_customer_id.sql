-- Threads the Square customer id through to the checkout-review-request send job, so the SMS
-- copy can look up which technician handled the customer's most recent visit (see
-- TechnicianNameResolver) without a second Square lookup at send time. Nullable: rows created
-- before this migration simply fall back to technician-less copy.
ALTER TABLE sms_reply_flow ADD COLUMN square_customer_id TEXT;
