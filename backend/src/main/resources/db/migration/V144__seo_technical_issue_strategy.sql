-- Real bug found live 2026-09-01: mobile and desktop PageSpeed checks share the same URL, but
-- seo_technical_issue's auto-resolve matching key (business_id, issue_type, url, query) had no way
-- to tell them apart — a "good" desktop LCP auto-resolved the still-real, still-open mobile LCP
-- issue for the same URL (and vice versa), producing a create-then-immediately-resolve loop every
-- sync instead of one stable tracked issue per strategy. Nullable since CTR_OPPORTUNITY issues
-- aren't strategy-scoped at all.
ALTER TABLE seo_technical_issue ADD COLUMN strategy TEXT;

DROP INDEX IF EXISTS idx_seo_technical_issue_subject;
CREATE INDEX idx_seo_technical_issue_subject
    ON seo_technical_issue (business_id, issue_type, url, query, strategy) WHERE resolved_at IS NULL;
