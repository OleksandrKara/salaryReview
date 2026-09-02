package com.salonreview.repo;

import com.salonreview.domain.SeoCompetitorPageSnapshot;
import com.salonreview.domain.SeoPageSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface SeoCompetitorPageSnapshotRepository extends JpaRepository<SeoCompetitorPageSnapshot, Long> {
    /** Upsert target for the weekly PageSpeed sync — same shape as {@code
     * SeoPageSnapshotRepository}'s own upsert lookup. */
    Optional<SeoCompetitorPageSnapshot> findByCompetitorIdAndDateAndStrategy(
            Long competitorId, LocalDate date, SeoPageSnapshot.Strategy strategy);

    Optional<SeoCompetitorPageSnapshot> findFirstByCompetitorIdAndStrategyOrderByDateDesc(
            Long competitorId, SeoPageSnapshot.Strategy strategy);
}
