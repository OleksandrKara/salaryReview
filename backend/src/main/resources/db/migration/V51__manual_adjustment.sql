-- Manual Credits, renamed and extended to Manual Adjustments: an owner/manager can now credit a
-- provider for a service Square recorded too messily to auto-attribute (positive gross, as before),
-- OR deduct a provider's commission for a service that was later refunded to the customer, or any
-- similar correction with no clean Square-side record (negative gross). Applied in the current
-- period, like Redo — never reopens an already-paid period. Column names kept as-is; only the
-- table (and its app-layer meaning) is renamed.
ALTER TABLE manual_credit RENAME TO manual_adjustment;
ALTER SEQUENCE manual_credit_id_seq RENAME TO manual_adjustment_id_seq;
