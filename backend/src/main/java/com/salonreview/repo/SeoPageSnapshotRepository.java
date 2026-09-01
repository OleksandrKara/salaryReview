package com.salonreview.repo;

import com.salonreview.domain.SeoPageSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SeoPageSnapshotRepository extends JpaRepository<SeoPageSnapshot, Long> {
    List<SeoPageSnapshot> findByBusinessIdOrderByDateDesc(Long businessId);

    Optional<SeoPageSnapshot> findFirstByBusinessIdAndUrlAndStrategyOrderByDateDesc(
            Long businessId, String url, SeoPageSnapshot.Strategy strategy);

    Optional<SeoPageSnapshot> findByBusinessIdAndDateAndUrlAndStrategy(
            Long businessId, java.time.LocalDate date, String url, SeoPageSnapshot.Strategy strategy);

    /** Dashboard's "latest Core Web Vitals" card — v1 only ever tracks one URL per business
     * (homepage-only, Open Question 2), so scoping by business+strategy alone is sufficient
     * without also needing to know that URL here. */
    Optional<SeoPageSnapshot> findFirstByBusinessIdAndStrategyOrderByDateDesc(
            Long businessId, SeoPageSnapshot.Strategy strategy);
}
