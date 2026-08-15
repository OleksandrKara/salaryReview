package com.salonreview.repo;

import com.salonreview.domain.RevenueSnapshot;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface RevenueSnapshotRepository extends JpaRepository<RevenueSnapshot, Long> {

    Optional<RevenueSnapshot> findByBusinessIdAndSnapshotDate(Long businessId, LocalDate snapshotDate);

    /** Calibration set: snapshots whose month has already closed and been filled in, newest first. */
    List<RevenueSnapshot> findAllByBusinessIdAndMonthEndActualIsNotNullOrderBySnapshotDateDesc(
            Long businessId, Pageable pageable);

    /** All snapshot rows in a date window — used by the monthly actual-fill job. */
    List<RevenueSnapshot> findAllByBusinessIdAndSnapshotDateBetween(Long businessId, LocalDate from, LocalDate to);
}
