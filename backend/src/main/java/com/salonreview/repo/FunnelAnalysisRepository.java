package com.salonreview.repo;

import com.salonreview.domain.FunnelAnalysis;
import com.salonreview.domain.Language;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FunnelAnalysisRepository extends JpaRepository<FunnelAnalysis, Long> {

    /** Cache lookup — an exact match on all five columns means the underlying funnel data is
     * unchanged since the last analysis in this same language, so the caller can skip the LLM
     * call entirely. */
    Optional<FunnelAnalysis> findFirstByLandingPageSlugAndVariantIdAndPromptVersionAndSnapshotFingerprintAndLanguageOrderByCreatedAtDesc(
            String landingPageSlug, UUID variantId, String promptVersion, String snapshotFingerprint, Language language);

    /** Owner-facing history list, newest first — capped at 20 since this is a low-frequency,
     * owner-triggered action, not something that accumulates thousands of rows. */
    List<FunnelAnalysis> findTop20ByLandingPageSlugAndVariantIdOrderByCreatedAtDesc(String landingPageSlug, UUID variantId);
}
