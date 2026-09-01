-- seo-monitoring-dashboard Phase 4 — V141's seo_technical_issue had no way to identify WHICH page
-- (LCP/CLS/INP) or WHICH query (CTR_OPPORTUNITY) an issue is actually about beyond a free-text
-- `detail` string, making correct auto-resolve matching impossible without fragile string parsing.
-- Caught before any real row exists (business_feature 'seo-monitoring.enabled' is still false for
-- every business), so this is a clean additive fix, not a real-data migration.
ALTER TABLE seo_technical_issue ADD COLUMN url TEXT;
ALTER TABLE seo_technical_issue ADD COLUMN query TEXT;

CREATE INDEX idx_seo_technical_issue_subject
    ON seo_technical_issue (business_id, issue_type, url, query) WHERE resolved_at IS NULL;
