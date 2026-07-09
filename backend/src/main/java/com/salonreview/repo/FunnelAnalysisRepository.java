package com.salonreview.repo;

import com.salonreview.domain.FunnelAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FunnelAnalysisRepository extends JpaRepository<FunnelAnalysis, Long> {

    /** Cache lookup — an exact match on all four columns means the underlying funnel data is
     * unchanged since the last analysis, so the caller can skip the LLM call entirely. */
    Optional<FunnelAnalysis> findFirstByLandingPageSlugAndFlowKeyAndPromptVersionAndSnapshotFingerprintOrderByCreatedAtDesc(
            String landingPageSlug, String flowKey, String promptVersion, String snapshotFingerprint);
}
