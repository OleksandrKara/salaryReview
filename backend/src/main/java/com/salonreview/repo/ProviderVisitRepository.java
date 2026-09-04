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

    /** Whether a real, settled visit (money actually collected for services rendered — see {@code
     * ProviderVisitIngestService}, which populates this table from the same source settlements
     * use) happened for this customer on this exact date, regardless of which provider. Added
     * 2026-09-04 after a real production near-miss: {@code TouchupReminderScheduler}/{@code
     * ColorBoosterReminderScheduler} were triggering off Square's raw Booking status alone
     * ("accepted, not cancelled") with no check that a real checkout ever happened — for business 2
     * (PMU, where a booking can exist purely from an online deposit invoice with no in-person
     * checkout to follow), a real check against live data found 55% of otherwise-"eligible"
     * bookings had no matching visit here at all. Any of that customer's providers counts — the
     * point is confirming a real visit happened that day, not which staff member it was. */
    boolean existsByBusinessIdAndCustomerIdAndServiceDate(Long businessId, String customerId, LocalDate serviceDate);

    @Transactional
    void deleteByBusinessIdAndServiceDateBetween(Long businessId, LocalDate from, LocalDate to);
}
