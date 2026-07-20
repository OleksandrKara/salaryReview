package com.salonreview.repo;

import com.salonreview.domain.AdSpendEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface AdSpendEntryRepository extends JpaRepository<AdSpendEntry, Long> {

    /** Every entry for this page whose [periodStart, periodEnd] overlaps [from, to] at all — the
     * proration math (see MarketingAnalyticsService/AdSpendResolver) decides how much of each
     * counts. */
    @Query("""
            SELECT e FROM AdSpendEntry e
            WHERE e.landingPageSlug = :slug
              AND e.periodStart <= :to AND e.periodEnd >= :from
            ORDER BY e.periodStart ASC
            """)
    List<AdSpendEntry> findOverlapping(@Param("slug") String slug, @Param("from") LocalDate from, @Param("to") LocalDate to);

    List<AdSpendEntry> findByLandingPageSlugOrderByPeriodStartDesc(String landingPageSlug);
}
