package com.salonreview.repo;

import com.salonreview.domain.IntakeForm;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface IntakeFormRepository extends JpaRepository<IntakeForm, Long> {

    List<IntakeForm> findByBusinessIdAndPhoneNumberIn(Long businessId, Collection<String> phoneNumbers);

    Optional<IntakeForm> findByToken(String token);

    Optional<IntakeForm> findByIdAndBusinessId(Long id, Long businessId);

    boolean existsByBusinessIdAndSquareCustomerIdIn(Long businessId, Collection<String> customerIds);

    List<IntakeForm> findByBusinessIdAndSquareCustomerIdIn(Long businessId, Collection<String> customerIds);

    List<IntakeForm> findByBusinessIdAndSquareBookingIdIn(Long businessId, Collection<String> bookingIds);

    List<IntakeForm> findByBusinessIdAndAppointmentStartAtBetweenOrderByAppointmentStartAtAsc(
            Long businessId, Instant from, Instant to);

    /** Reminder candidates: sent, still unsigned, no reminder decision yet, visit inside the window. */
    List<IntakeForm> findByBusinessIdAndStatusAndReminderSmsStateIsNullAndAppointmentStartAtBetween(
            Long businessId, String status, Instant from, Instant to);

    /** Initial-send candidates not yet attempted. */
    List<IntakeForm> findByStatusAndInitialSmsStateIsNull(String status);

    @Query("select f from IntakeForm f where f.squareSyncAttempts < :maxAttempts "
            + "and f.status <> 'PENDING' and (f.squareSyncedStatus is null "
            + "or (f.status = 'SIGNED' and f.squareSyncedStatus <> 'SIGNED2') "
            + "or (f.status <> 'SIGNED' and f.squareSyncedStatus <> f.status))")
    List<IntakeForm> findNeedingSquareSync(@Param("maxAttempts") int maxAttempts);
}
