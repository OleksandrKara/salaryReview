package com.salonreview.repo;

import com.salonreview.domain.FunnelAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FunnelAnalysisRepository extends JpaRepository<FunnelAnalysis, Long> {

    /** Cache lookup — an exact match on all four columns means the underlying funnel data is
     * unchanged since the last analysis, so the caller can skip the LLM call entirely. */
    Optional<FunnelAnalysis> findFirstByLandingPageSlugAndFlowKeyAndPromptVersionAndSnapshotFingerprintOrderByCreatedAtDesc(
            String landingPageSlug, String flowKey, String promptVersion, String snapshotFingerprint);

    /** Owner-facing history list, newest first — capped at 20 since this is a low-frequency,
     * owner-triggered action, not something that accumulates thousands of rows. */
    List<FunnelAnalysis> findTop20ByLandingPageSlugAndFlowKeyOrderByCreatedAtDesc(String landingPageSlug, String flowKey);
}
