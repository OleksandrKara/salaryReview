-- seo-monitoring-dashboard Phase 1 (design.md D5) — explicit false row for every existing
-- business, not left absent, so intent is legible rather than relying on business_feature's
-- absent-means-disabled convention. Unlike V108's AI/RAG seed (which only inserted for business
-- id=1, leaving business 2 absent on purpose), this key genuinely starts false for everyone,
-- including AK.LUX.NAILS — SEO monitoring is opt-in per business, turned on only after an owner
-- connects real credentials through the settings page (Phase 7).
INSERT INTO business_feature (business_id, feature_key, enabled)
SELECT id, 'seo-monitoring.enabled', false
FROM business
WHERE NOT EXISTS (
    SELECT 1 FROM business_feature
    WHERE business_feature.business_id = business.id
      AND business_feature.feature_key = 'seo-monitoring.enabled'
);
