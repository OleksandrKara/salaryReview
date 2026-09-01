package com.salonreview.repo;

import com.salonreview.domain.SeoSearchMetricsSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SeoSearchMetricsSnapshotRepository extends JpaRepository<SeoSearchMetricsSnapshot, Long> {
    List<SeoSearchMetricsSnapshot> findByBusinessIdAndDateBetweenOrderByDateAsc(
            Long businessId, LocalDate start, LocalDate end);

    List<SeoSearchMetricsSnapshot> findByBusinessIdAndDate(Long businessId, LocalDate date);

    /** Upsert target — {@code SearchConsoleClient.queryPerformance} always requests both
     * dimensions, so {@code page} is populated in practice, but this stays a plain equality lookup
     * (not the null-safe pattern {@code SeoTechnicalIssueRepository} needs) since a missing page
     * value here would just mean two rows never de-duplicate, not a correctness bug. */
    Optional<SeoSearchMetricsSnapshot> findByBusinessIdAndDateAndQueryAndPage(
            Long businessId, LocalDate date, String query, String page);
}
