-- Providers now approve / request a correction per PERIOD (1-15 and 16-end), not for the whole month.
-- Add a `half` to the feedback row and key it by (provider, year, month, half). Existing month-level
-- feedback is applied to BOTH halves so nothing is lost (drop the old unique key before duplicating).
ALTER TABLE settlement_feedback ADD COLUMN half VARCHAR(10);

ALTER TABLE settlement_feedback DROP CONSTRAINT settlement_feedback_provider_id_year_month_key;

UPDATE settlement_feedback SET half = 'SECOND' WHERE half IS NULL;

INSERT INTO settlement_feedback (provider_id, year, month, status, comment, half, created_at, updated_at)
SELECT provider_id, year, month, status, comment, 'FIRST', created_at, updated_at
  FROM settlement_feedback WHERE half = 'SECOND';

ALTER TABLE settlement_feedback ALTER COLUMN half SET NOT NULL;
ALTER TABLE settlement_feedback ADD CONSTRAINT settlement_feedback_half_chk CHECK (half IN ('FIRST', 'SECOND'));
ALTER TABLE settlement_feedback ADD CONSTRAINT settlement_feedback_uq UNIQUE (provider_id, year, month, half);
