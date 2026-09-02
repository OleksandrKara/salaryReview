package com.salonreview.repo;

import com.salonreview.domain.SeoCompetitor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SeoCompetitorRepository extends JpaRepository<SeoCompetitor, Long> {
    List<SeoCompetitor> findByBusinessIdOrderByCreatedAtAsc(Long businessId);

    List<SeoCompetitor> findByBusinessIdAndActiveTrueOrderByCreatedAtAsc(Long businessId);

    /** Business-scoped lookup for any caller-controlled id (edit/delete) — never a bare {@code
     * findById}, per this app's own multi-tenant convention. */
    Optional<SeoCompetitor> findByIdAndBusinessId(Long id, Long businessId);
}
