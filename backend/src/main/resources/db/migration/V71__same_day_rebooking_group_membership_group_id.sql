-- Which Square customer group a membership row actually belongs to — needed now that a second
-- promo (lapsed_customer_winback, $5) enrolls into its own separate group alongside the original
-- same_day_rebooking_discount ($10) one. Nullable: existing rows predate this column and were
-- all $10 enrollments (the $5 group didn't exist yet); the expiry scheduler falls back to the
-- $10 group id for any row where this is null, preserving exact prior behavior for them.
ALTER TABLE same_day_rebooking_group_membership ADD COLUMN group_id text;
