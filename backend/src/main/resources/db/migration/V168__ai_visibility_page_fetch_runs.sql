-- ai-visibility page comparison (design.md D10): page fetches that cost money (DataForSEO On-Page
-- fallback) are recorded as runs so the monthly budget guard sees them.
ALTER TABLE ai_visibility_run DROP CONSTRAINT ai_visibility_run_trigger_check;
ALTER TABLE ai_visibility_run ADD CONSTRAINT ai_visibility_run_trigger_check CHECK (trigger IN ('SCHEDULED','MANUAL_QUERY','PAGE_FETCH'));
