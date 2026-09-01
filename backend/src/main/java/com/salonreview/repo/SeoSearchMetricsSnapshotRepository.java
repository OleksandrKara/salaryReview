package com.salonreview.repo;

import com.salonreview.domain.SeoSearchMetricsSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface SeoSearchMetricsSnapshotRepository extends JpaRepository<SeoSearchMetricsSnapshot, Long> {
    List<SeoSearchMetricsSnapshot> findByBusinessIdAndDateBetweenOrderByDateAsc(
            Long businessId, LocalDate start, LocalDate end);
}
