-- Phase 2.4: business_id on tables with no FK path to business at all (design.md's "ambiguous,
-- needs a bolted-on column" classification) — these are keyed only by Square IDs or raw reference
-- strings, not by provider_id/user_id, so there's no join to inherit scope through (unlike Phase
-- 2.3's tables). Additive only, per tasks.md's rollback strategy: existing unique constraints on the
-- Square-ID columns are untouched.

ALTER TABLE owner_customer ADD COLUMN business_id BIGINT REFERENCES business(id);
ALTER TABLE suspicious_booking_clearance ADD COLUMN business_id BIGINT REFERENCES business(id);
ALTER TABLE cancellation_clearance ADD COLUMN business_id BIGINT REFERENCES business(id);
ALTER TABLE suspicious_triage ADD COLUMN business_id BIGINT REFERENCES business(id);
ALTER TABLE prepaid_package ADD COLUMN business_id BIGINT REFERENCES business(id);
ALTER TABLE provider_visit ADD COLUMN business_id BIGINT REFERENCES business(id);

UPDATE owner_customer SET business_id = (SELECT id FROM business WHERE short_code = 'akluxnails');
UPDATE suspicious_booking_clearance SET business_id = (SELECT id FROM business WHERE short_code = 'akluxnails');
UPDATE cancellation_clearance SET business_id = (SELECT id FROM business WHERE short_code = 'akluxnails');
UPDATE suspicious_triage SET business_id = (SELECT id FROM business WHERE short_code = 'akluxnails');
UPDATE prepaid_package SET business_id = (SELECT id FROM business WHERE short_code = 'akluxnails');
UPDATE provider_visit SET business_id = (SELECT id FROM business WHERE short_code = 'akluxnails');

ALTER TABLE owner_customer ALTER COLUMN business_id SET NOT NULL;
ALTER TABLE suspicious_booking_clearance ALTER COLUMN business_id SET NOT NULL;
ALTER TABLE cancellation_clearance ALTER COLUMN business_id SET NOT NULL;
ALTER TABLE suspicious_triage ALTER COLUMN business_id SET NOT NULL;
ALTER TABLE prepaid_package ALTER COLUMN business_id SET NOT NULL;
ALTER TABLE provider_visit ALTER COLUMN business_id SET NOT NULL;
