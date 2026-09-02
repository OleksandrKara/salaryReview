package com.salonreview.config;

import com.salonreview.repo.BusinessFeatureRepository;
import org.springframework.stereotype.Service;

/**
 * Phase 4.3 (multi-tenant-salon-platform) — per-business gate layered on top of each feature's
 * existing deployment-level {@code @ConfigurationProperties} flag (e.g. {@link RagProperties},
 * {@link AiTriageProperties}). The deployment-level flag answers "is this feature even possible
 * on this deployment" (API keys configured, etc.); this answers "does this specific business
 * have it turned on" — every call site should check both, e.g.
 * {@code ragProperties.isEnabled() && businessFeatures.isEnabled(businessId, RAG_ENABLED)}.
 *
 * <p>A missing {@code business_feature} row means disabled — ships-dark-per-business, same
 * convention as the deployment-level flags themselves (see V108's own migration comment for why
 * Business A is seeded {@code enabled=true} for all 5 keys and every other business gets none).
 */
@Service
public class BusinessFeatureService {

    public static final String RAG_ENABLED = "rag.enabled";
    public static final String RAG_SUGGESTIONS_ENABLED = "rag.suggestions.enabled";
    public static final String AI_TRIAGE_ENABLED = "ai.triage.enabled";
    public static final String AI_FUNNEL_ANALYSIS_ENABLED = "ai.funnel-analysis.enabled";
    public static final String AI_SMS_DRAFT_ENABLED = "ai.sms-draft.enabled";
    public static final String AI_SEO_ADVISOR_ENABLED = "ai.seo-advisor.enabled";
    /** Matches the literal key already seeded by V142__business_feature_seo_monitoring.sql —
     * seo-monitoring-dashboard design.md D5. Hyphenated rather than dot-namespaced like the keys
     * above since that migration (and tasks.md) already fixed this exact string first. */
    public static final String SEO_MONITORING_ENABLED = "seo-monitoring.enabled";

    private final BusinessFeatureRepository repository;

    public BusinessFeatureService(BusinessFeatureRepository repository) {
        this.repository = repository;
    }

    public boolean isEnabled(Long businessId, String featureKey) {
        return repository.findByBusinessIdAndFeatureKey(businessId, featureKey)
                .map(com.salonreview.domain.BusinessFeature::isEnabled)
                .orElse(false);
    }
}
