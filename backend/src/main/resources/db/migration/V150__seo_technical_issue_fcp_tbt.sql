-- Bug fix: seo-intelligence-advisor Phase 1 (PR #539) added FCP/TBT to SeoTechnicalIssue.IssueType
-- and started calling issueRepository.save(...) with those values, but never widened this table's
-- own CHECK constraint (from V141) to allow them. Any real poor-FCP/TBT PageSpeed result would hit
-- a CHECK violation here — worse, since SeoSyncService.syncPageSpeed wraps both strategies in one
-- @Transactional method with no per-strategy savepoint, that violation would poison the whole
-- Postgres transaction, silently failing the OTHER (working) strategy's sync too, not just this
-- metric. Found via manual E2E seeding while verifying Phase 8 (never caught by unit tests, since
-- SeoIssueFlaggingServiceTest mocks the repository and never touches a real CHECK constraint).
ALTER TABLE seo_technical_issue DROP CONSTRAINT seo_technical_issue_issue_type_check;
ALTER TABLE seo_technical_issue ADD CONSTRAINT seo_technical_issue_issue_type_check
    CHECK (issue_type IN ('LCP', 'CLS', 'INP', 'FCP', 'TBT', 'CTR_OPPORTUNITY'));
