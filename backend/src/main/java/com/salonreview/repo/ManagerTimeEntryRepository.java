package com.salonreview.repo;

import com.salonreview.domain.ManagerTimeEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ManagerTimeEntryRepository extends JpaRepository<ManagerTimeEntry, Long> {

    List<ManagerTimeEntry> findByUserIdAndWorkDateBetweenOrderByStartAtAsc(
            Long userId, LocalDate from, LocalDate to);

    /** All managers' entries in a period — for the owner timesheet (grouped by user in the service).
     * {@code user_id} has no mapped @ManyToOne here (plain FK column), so tenant scoping is an
     * explicit join against {@code app_user.business_id} rather than a path expression. */
    @Query("select e from ManagerTimeEntry e join AppUser u on u.id = e.userId "
            + "where u.businessId = :businessId and e.workDate between :from and :to")
    List<ManagerTimeEntry> findByBusinessIdAndWorkDateBetween(@Param("businessId") Long businessId,
                                                               @Param("from") LocalDate from,
                                                               @Param("to") LocalDate to);

    /** The manager's currently-open shift, if any (end_at is null). */
    Optional<ManagerTimeEntry> findByUserIdAndEndAtIsNull(Long userId);

    /** Every currently-open shift across managers — for the owner's "clocked in now" flags. */
    @Query("select e from ManagerTimeEntry e join AppUser u on u.id = e.userId "
            + "where u.businessId = :businessId and e.endAt is null")
    List<ManagerTimeEntry> findByBusinessIdAndEndAtIsNull(@Param("businessId") Long businessId);
}
