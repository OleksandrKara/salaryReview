-- Found live 2026-08-18 while picking up tasks.md 2.4/2.6's deferred follow-up: V88 and V91 added
-- business_id to owner_customer, suspicious_booking_clearance, cancellation_clearance,
-- suspicious_triage, and no_show_fee_override, but (by their own comments, "additive only") never
-- widened each table's existing single-column unique constraint on the Square-ID column(s) to
-- include it. That gap was directly exploitable: OwnerCustomerService#delete,
-- SuspiciousBookingService#clear/unclear, CancelledAppointmentService#clear/unclear,
-- NoShowFeeService#confirm/suppress/clearOverride, and SuspiciousBookingTriageService's cache
-- lookup + feedback recording all resolved rows by the bare Square ID (a caller-controlled path
-- variable or request body field) with no business filter — any business could read, mutate, or
-- delete another business's row by supplying (or guessing) its Square booking/customer id. Fixed
-- in the same change as this migration: every affected repository method and its callers now
-- scope by business_id first.
--
-- No historical exploitation found: business 2 (AK PMU) has zero rows in any of these five tables
-- as of this migration (checked directly against production before writing this fix) — the gap
-- was real and live, but never actually crossed.

ALTER TABLE owner_customer
    DROP CONSTRAINT owner_customer_square_customer_id_key,
    ADD CONSTRAINT owner_customer_business_square_customer_uq UNIQUE (business_id, square_customer_id);

ALTER TABLE suspicious_booking_clearance
    DROP CONSTRAINT suspicious_booking_clearance_square_booking_id_key,
    ADD CONSTRAINT suspicious_booking_clearance_business_booking_uq UNIQUE (business_id, square_booking_id);

ALTER TABLE cancellation_clearance
    DROP CONSTRAINT cancellation_clearance_square_booking_id_key,
    ADD CONSTRAINT cancellation_clearance_business_booking_uq UNIQUE (business_id, square_booking_id);

ALTER TABLE no_show_fee_override
    DROP CONSTRAINT no_show_fee_override_square_booking_id_key,
    ADD CONSTRAINT no_show_fee_override_business_booking_uq UNIQUE (business_id, square_booking_id);

ALTER TABLE suspicious_triage
    DROP CONSTRAINT uq_suspicious_triage_booking_prompt,
    ADD CONSTRAINT uq_suspicious_triage_business_booking_prompt UNIQUE (business_id, square_booking_id, prompt_version);
