-- Which language the LLM was instructed to write this analysis in (EN/RU) — the cache lookup in
-- FunnelAnalysisService now includes this column so an English-preferring and a Russian-preferring
-- owner never get served each other's cached result for the same funnel snapshot. Existing rows
-- predate this feature and were all generated in English.
ALTER TABLE funnel_analysis ADD COLUMN language VARCHAR(8) NOT NULL DEFAULT 'EN';

DROP INDEX idx_funnel_analysis_lookup;
CREATE INDEX idx_funnel_analysis_lookup
    ON funnel_analysis (landing_page_slug, flow_key, prompt_version, language, created_at DESC);
