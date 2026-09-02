package com.salonreview.repo;

import com.salonreview.domain.Language;
import com.salonreview.domain.SeoAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SeoAnalysisRepository extends JpaRepository<SeoAnalysis, Long> {
    Optional<SeoAnalysis> findFirstByBusinessIdAndPromptVersionAndSnapshotFingerprintAndLanguageOrderByCreatedAtDesc(
            Long businessId, String promptVersion, String snapshotFingerprint, Language language);

    List<SeoAnalysis> findTop20ByBusinessIdOrderByCreatedAtDesc(Long businessId);

    /** Feeds {@code SeoContextBuilderService}'s "previous AI recommendations" section — capped
     * small (design.md D8's context budget), not the full history list above. */
    List<SeoAnalysis> findTop3ByBusinessIdOrderByCreatedAtDesc(Long businessId);
}
