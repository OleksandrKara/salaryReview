package com.salonreview.repo;

import com.salonreview.domain.SeoTrackedKeyword;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SeoTrackedKeywordRepository extends JpaRepository<SeoTrackedKeyword, Long> {
    List<SeoTrackedKeyword> findByBusinessIdOrderByCreatedAtAsc(Long businessId);

    /** Business-scoped lookup for any caller-controlled id (edit/delete) — never a bare {@code
     * findById}, per this app's own multi-tenant convention (see openspec/config.yaml's context
     * note on cross-tenant isolation). */
    Optional<SeoTrackedKeyword> findByIdAndBusinessId(Long id, Long businessId);
}
