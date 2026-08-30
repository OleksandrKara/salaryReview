-- Analyses were keyed by (landing_page_slug, flow_key) — correct back when a page had at most one
-- live variant per flow_key. Multiple variants can now share the same flow_key (e.g. mani's
-- Version_7 and Precision Studio both use contactStepPosition=end / mani_booking_v2), so flow_key
-- alone no longer identifies a single funnel. Re-keyed on variant_id instead. Nullable: rows
-- written before this migration have no variant_id and are simply no longer matched by new
-- lookups — kept for history, not backfilled (there's no reliable way to attribute an old analysis
-- to one specific historical variant after the fact).
ALTER TABLE funnel_analysis ADD COLUMN variant_id UUID;

CREATE INDEX idx_funnel_analysis_variant_lookup
    ON funnel_analysis (landing_page_slug, variant_id, prompt_version, created_at DESC);
