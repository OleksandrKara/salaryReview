package com.salonreview.repo;

import com.salonreview.domain.RevenueSnapshot;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface RevenueSnapshotRepository extends JpaRepository<RevenueSnapshot, Long> {

    Optional<RevenueSnapshot> findBySnapshotDate(LocalDate snapshotDate);

    Optional<RevenueSnapshot> findTopByOrderBySnapshotDateDesc();

    /** Calibration set: snapshots whose month has already closed and been filled in, newest first. */
    List<RevenueSnapshot> findAllByMonthEndActualIsNotNullOrderBySnapshotDateDesc(Pageable pageable);

    /** All snapshot rows in a date window — used by the monthly actual-fill job. */
    List<RevenueSnapshot> findAllBySnapshotDateBetween(LocalDate from, LocalDate to);
}
