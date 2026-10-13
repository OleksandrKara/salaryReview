package com.salonreview.repo;

import com.salonreview.domain.AiVisibilityRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AiVisibilityRunRepository extends JpaRepository<AiVisibilityRun, Long> {
    Optional<AiVisibilityRun> findByIdAndBusinessId(Long id, Long businessId);

    List<AiVisibilityRun> findTop2ByBusinessIdAndTriggerAndFinishedAtIsNotNullOrderByStartedAtDesc(Long businessId, AiVisibilityRun.Trigger trigger);

    Optional<AiVisibilityRun> findTop1ByBusinessIdOrderByStartedAtDesc(Long businessId);

    List<AiVisibilityRun> findByFinishedAtIsNullAndStartedAtBefore(Instant before);

    List<AiVisibilityRun> findByBusinessIdAndTriggerAndStartedAtAfter(Long businessId, AiVisibilityRun.Trigger trigger, Instant after);

    @Query("select coalesce(sum(r.costUsd), 0) from AiVisibilityRun r where r.businessId = :businessId and r.startedAt >= :since")
    BigDecimal spentSince(@Param("businessId") Long businessId, @Param("since") Instant since);
}
