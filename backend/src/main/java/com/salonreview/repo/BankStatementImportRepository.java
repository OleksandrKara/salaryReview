package com.salonreview.repo;

import com.salonreview.domain.BankStatementImport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface BankStatementImportRepository extends JpaRepository<BankStatementImport, Long> {

    List<BankStatementImport> findAllByOrderByUploadedAtDesc();

    /** Whether any COMPLETED import's statement period overlaps [from, to] at all — drives the
     * statement-covered-month exclusivity rule (openspec design.md D11). A null period bound (an
     * older import predating period detection) is treated as covering the whole range, so it is
     * still counted as coverage rather than silently ignored. */
    @Query("""
            SELECT COUNT(i) > 0 FROM BankStatementImport i
            WHERE i.status = 'COMPLETED'
            AND (i.statementPeriodStart IS NULL OR i.statementPeriodStart <= :to)
            AND (i.statementPeriodEnd IS NULL OR i.statementPeriodEnd >= :from)
            """)
    boolean existsCompletedOverlapping(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
