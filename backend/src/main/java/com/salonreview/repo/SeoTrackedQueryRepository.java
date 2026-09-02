package com.salonreview.repo;

import com.salonreview.domain.SeoTrackedQuery;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SeoTrackedQueryRepository extends JpaRepository<SeoTrackedQuery, Long> {
    List<SeoTrackedQuery> findByBusinessIdOrderByCreatedAtAsc(Long businessId);

    Optional<SeoTrackedQuery> findByBusinessIdAndQuery(Long businessId, String query);
}
