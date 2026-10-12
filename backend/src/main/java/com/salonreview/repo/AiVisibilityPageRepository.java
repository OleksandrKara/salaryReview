package com.salonreview.repo;

import com.salonreview.domain.AiVisibilityPage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AiVisibilityPageRepository extends JpaRepository<AiVisibilityPage, Long> {
    Optional<AiVisibilityPage> findByBusinessIdAndUrl(Long businessId, String url);

    List<AiVisibilityPage> findByBusinessIdAndUrlIn(Long businessId, Collection<String> urls);

    long countByBusinessIdAndLastFetchedAtAfterAndFetchMethodIsNotNull(Long businessId, Instant after);
}
