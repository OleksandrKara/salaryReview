-- ad_spend_entries had no business_id column at all, so every business's ad-spend GET/PUT/DELETE
-- endpoint (MarketingAdsReportController) operated on every entry regardless of which business
-- created it — a business could read, edit, or delete another business's spend entries by
-- guessing a sequential id. Backfilled to 1 (AK.LUX.NAILS, the only business with real ad-spend
-- data entered so far) since every existing row predates business 2 (AK PMU).
ALTER TABLE ad_spend_entries ADD COLUMN IF NOT EXISTS business_id BIGINT;
UPDATE ad_spend_entries SET business_id = 1 WHERE business_id IS NULL;
ALTER TABLE ad_spend_entries ALTER COLUMN business_id SET NOT NULL;
CREATE INDEX IF NOT EXISTS idx_ad_spend_entries_business_id ON ad_spend_entries (business_id);
