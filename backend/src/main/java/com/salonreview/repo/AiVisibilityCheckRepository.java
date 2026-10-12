package com.salonreview.repo;

import com.salonreview.domain.AiVisibilityCheck;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface AiVisibilityCheckRepository extends JpaRepository<AiVisibilityCheck, Long> {
    List<AiVisibilityCheck> findByBusinessIdOrderByCheckedAtDesc(Long businessId);

    List<AiVisibilityCheck> findByBusinessIdAndCheckedAtAfterOrderByCheckedAtDesc(Long businessId, Instant after);

    List<AiVisibilityCheck> findByBusinessIdAndQueryIdOrderByCheckedAtDesc(Long businessId, Long queryId);

    List<AiVisibilityCheck> findByBusinessIdAndRunId(Long businessId, Long runId);

    boolean existsByBusinessIdAndQueryIdAndRunIdIn(Long businessId, Long queryId, List<Long> runIds);
}
