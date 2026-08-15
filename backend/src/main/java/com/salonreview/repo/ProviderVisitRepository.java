package com.salonreview.repo;

import com.salonreview.domain.ProviderVisit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

public interface ProviderVisitRepository extends JpaRepository<ProviderVisit, Long> {

    /** The whole ledger, oldest first — analytics loads this and computes in memory (one salon's volume). */
    List<ProviderVisit> findAllByBusinessIdOrderByServiceDateAsc(Long businessId);

    /** Visits within a month — used to re-ingest idempotently (delete the month, then reinsert). */
    List<ProviderVisit> findByBusinessIdAndServiceDateBetween(Long businessId, LocalDate from, LocalDate to);

    long countByBusinessIdAndServiceDateBetween(Long businessId, LocalDate from, LocalDate to);

    @Transactional
    void deleteByBusinessIdAndServiceDateBetween(Long businessId, LocalDate from, LocalDate to);
}
