package com.salonreview.repo;

import com.salonreview.domain.SeoPageSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SeoPageSnapshotRepository extends JpaRepository<SeoPageSnapshot, Long> {
    List<SeoPageSnapshot> findByBusinessIdOrderByDateDesc(Long businessId);

    Optional<SeoPageSnapshot> findFirstByBusinessIdAndUrlAndStrategyOrderByDateDesc(
            Long businessId, String url, SeoPageSnapshot.Strategy strategy);
}
