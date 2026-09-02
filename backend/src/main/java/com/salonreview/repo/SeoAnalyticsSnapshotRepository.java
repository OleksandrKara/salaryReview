package com.salonreview.repo;

import com.salonreview.domain.SeoAnalyticsSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SeoAnalyticsSnapshotRepository extends JpaRepository<SeoAnalyticsSnapshot, Long> {
    List<SeoAnalyticsSnapshot> findByBusinessIdAndDateBetweenOrderByDateAsc(
            Long businessId, LocalDate start, LocalDate end);

    Optional<SeoAnalyticsSnapshot> findByBusinessIdAndDate(Long businessId, LocalDate date);
}
