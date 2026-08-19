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
            WHERE e.businessId = :businessId AND e.landingPageSlug = :slug
              AND e.periodStart <= :to AND e.periodEnd >= :from
            ORDER BY e.periodStart ASC
            """)
    List<AdSpendEntry> findOverlapping(@Param("slug") String slug, @Param("from") LocalDate from, @Param("to") LocalDate to,
                                        @Param("businessId") Long businessId);

    List<AdSpendEntry> findByLandingPageSlugAndBusinessIdOrderByPeriodStartDesc(String landingPageSlug, Long businessId);

    /** Business-scoped id lookup — the choke point that stops one business's owner/ads-manager
     * from reading, editing, or deleting another business's spend entry by guessing a sequential
     * id (previously possible: {@code findById}/{@code existsById}/{@code deleteById} have no
     * ownership check of their own). */
    java.util.Optional<AdSpendEntry> findByIdAndBusinessId(Long id, Long businessId);

    boolean existsByIdAndBusinessId(Long id, Long businessId);

    void deleteByIdAndBusinessId(Long id, Long businessId);
}
