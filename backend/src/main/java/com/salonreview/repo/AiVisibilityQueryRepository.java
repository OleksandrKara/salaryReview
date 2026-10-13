package com.salonreview.repo;

import com.salonreview.domain.AiVisibilityQuery;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AiVisibilityQueryRepository extends JpaRepository<AiVisibilityQuery, Long> {
    Optional<AiVisibilityQuery> findByIdAndBusinessId(Long id, Long businessId);

    List<AiVisibilityQuery> findByBusinessIdAndStatusInOrderBySortOrderAscIdAsc(Long businessId, Collection<AiVisibilityQuery.Status> statuses);

    long countByBusinessIdAndStatusIn(Long businessId, Collection<AiVisibilityQuery.Status> statuses);

    /** The 20-question cap counts questions asked to every engine; Google-only ones (cheap) don't count. */
    long countByBusinessIdAndStatusInAndEnginesIsNull(Long businessId, Collection<AiVisibilityQuery.Status> statuses);

    boolean existsByBusinessIdAndQueryTextIgnoreCase(Long businessId, String queryText);
}
