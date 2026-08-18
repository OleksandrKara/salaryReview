-- Phase 4.4 (multi-tenant-salon-platform): promotes NoShowFeeService's hardcoded $25.00 to a
-- nullable per-business setting. Null means the no-show fee program is off entirely for that
-- business (no auto-detection, no confirmable default amount) — see NoShowFeeService's own doc.
-- Business A (id=1) keeps its historical $25 exactly; every other business starts null (off),
-- per Phase 0.5's resolution for AK PMU ("none, no_show_fee_amount = null").
ALTER TABLE salon_config ADD COLUMN no_show_fee_amount NUMERIC(10,2);

UPDATE salon_config SET no_show_fee_amount = 25.00 WHERE business_id = 1;
