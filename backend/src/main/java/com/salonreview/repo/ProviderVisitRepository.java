package com.salonreview.repo;

import com.salonreview.domain.ProviderVisit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

public interface ProviderVisitRepository extends JpaRepository<ProviderVisit, Long> {

    /** The whole ledger, oldest first — analytics loads this and computes in memory (one salon's volume). */
    List<ProviderVisit> findAllByBusinessIdOrderByServiceDateAsc(Long businessId);

    /** Most-recent-visit-first, capped via {@code pageable} — used by
     * {@code WinbackEmailFallbackScheduler} (via {@code Pageable.ofSize(1)}) to re-derive which
     * technician to name in the evening email follow-up, since neither {@code sms_message} nor the
     * win-back send-log tables persist it from the morning's SMS send. */
    List<ProviderVisit> findByBusinessIdAndCustomerIdOrderByServiceDateDesc(Long businessId, String customerId, Pageable pageable);

    /** Visits within a month — used to re-ingest idempotently (delete the month, then reinsert). */
    List<ProviderVisit> findByBusinessIdAndServiceDateBetween(Long businessId, LocalDate from, LocalDate to);

    long countByBusinessIdAndServiceDateBetween(Long businessId, LocalDate from, LocalDate to);

    @Transactional
    void deleteByBusinessIdAndServiceDateBetween(Long businessId, LocalDate from, LocalDate to);
}
