-- Continuing tasks.md 3.7 past PR #382: same_day_rebooking_send, same_day_rebooking_group_membership
-- (V55), repeat_customer_winback_send (V72), and lapsed_customer_winback_send (V68) are root tables
-- keyed only by a raw square_customer_id string — zero tenant boundary, same gap as sms_message/
-- sms_reply_flow/sms_automation before them. In practice every existing row already belongs to
-- Business A (no second business has ever had Twilio configured), so each backfill below is a
-- straightforward "everything to Business A".
ALTER TABLE same_day_rebooking_send ADD COLUMN business_id BIGINT;
UPDATE same_day_rebooking_send SET business_id = (SELECT id FROM business WHERE short_code = 'akluxnails');
ALTER TABLE same_day_rebooking_send ALTER COLUMN business_id SET NOT NULL;
ALTER TABLE same_day_rebooking_send ADD CONSTRAINT same_day_rebooking_send_business_id_fkey
    FOREIGN KEY (business_id) REFERENCES business (id);
CREATE INDEX idx_same_day_rebooking_send_business_id ON same_day_rebooking_send (business_id);

ALTER TABLE same_day_rebooking_group_membership ADD COLUMN business_id BIGINT;
UPDATE same_day_rebooking_group_membership SET business_id = (SELECT id FROM business WHERE short_code = 'akluxnails');
ALTER TABLE same_day_rebooking_group_membership ALTER COLUMN business_id SET NOT NULL;
ALTER TABLE same_day_rebooking_group_membership ADD CONSTRAINT same_day_rebooking_group_membership_business_id_fkey
    FOREIGN KEY (business_id) REFERENCES business (id);
CREATE INDEX idx_same_day_rebooking_group_membership_business_id ON same_day_rebooking_group_membership (business_id);

ALTER TABLE repeat_customer_winback_send ADD COLUMN business_id BIGINT;
UPDATE repeat_customer_winback_send SET business_id = (SELECT id FROM business WHERE short_code = 'akluxnails');
ALTER TABLE repeat_customer_winback_send ALTER COLUMN business_id SET NOT NULL;
ALTER TABLE repeat_customer_winback_send ADD CONSTRAINT repeat_customer_winback_send_business_id_fkey
    FOREIGN KEY (business_id) REFERENCES business (id);
CREATE INDEX idx_repeat_customer_winback_send_business_id ON repeat_customer_winback_send (business_id);

ALTER TABLE lapsed_customer_winback_send ADD COLUMN business_id BIGINT;
UPDATE lapsed_customer_winback_send SET business_id = (SELECT id FROM business WHERE short_code = 'akluxnails');
ALTER TABLE lapsed_customer_winback_send ALTER COLUMN business_id SET NOT NULL;
ALTER TABLE lapsed_customer_winback_send ADD CONSTRAINT lapsed_customer_winback_send_business_id_fkey
    FOREIGN KEY (business_id) REFERENCES business (id);
CREATE INDEX idx_lapsed_customer_winback_send_business_id ON lapsed_customer_winback_send (business_id);
