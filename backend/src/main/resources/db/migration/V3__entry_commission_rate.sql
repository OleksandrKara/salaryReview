-- Per-entry commission rate override.
-- If NULL, the calculator falls back to providers.commission_rate.
ALTER TABLE period_entries
    ADD COLUMN commission_rate NUMERIC(5,4) NULL;
