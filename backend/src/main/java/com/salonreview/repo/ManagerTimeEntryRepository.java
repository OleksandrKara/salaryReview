package com.salonreview.repo;

import com.salonreview.domain.ManagerTimeEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ManagerTimeEntryRepository extends JpaRepository<ManagerTimeEntry, Long> {

    List<ManagerTimeEntry> findByUserIdAndWorkDateBetweenOrderByStartAtAsc(
            Long userId, LocalDate from, LocalDate to);

    /** All managers' entries in a period — for the owner timesheet (grouped by user in the service). */
    List<ManagerTimeEntry> findByWorkDateBetween(LocalDate from, LocalDate to);

    /** The manager's currently-open shift, if any (end_at is null). */
    Optional<ManagerTimeEntry> findByUserIdAndEndAtIsNull(Long userId);

    /** Every currently-open shift across managers — for the owner's "clocked in now" flags. */
    List<ManagerTimeEntry> findByEndAtIsNull();
}
