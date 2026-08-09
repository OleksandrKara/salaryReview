package com.salonreview.repo;

import com.salonreview.domain.ExpenseEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface ExpenseEntryRepository extends JpaRepository<ExpenseEntry, Long> {

    /** Every entry whose [periodStart, periodEnd] overlaps [from, to] at all — the proration math
     * (see ExpenseService/ExpenseResolver) decides how much of each counts. Not category-scoped:
     * an owner reporting period wants the total across every expense category, not one at a time. */
    @Query("""
            SELECT e FROM ExpenseEntry e
            WHERE e.periodStart <= :to AND e.periodEnd >= :from
            ORDER BY e.periodStart ASC
            """)
    List<ExpenseEntry> findOverlapping(@Param("from") LocalDate from, @Param("to") LocalDate to);

    List<ExpenseEntry> findAllByOrderByPeriodStartDesc();

    boolean existsByCategory(String category);
}
