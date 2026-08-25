package com.salonreview.repo;

import com.salonreview.domain.ServiceLifecycleReminderSend;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.time.LocalDate;

public interface ServiceLifecycleReminderSendRepository extends JpaRepository<ServiceLifecycleReminderSend, Long> {
    boolean existsByBusinessIdAndAutomationKeyAndSquareCustomerIdAndTriggerServiceDate(
            Long businessId, String automationKey, String squareCustomerId, LocalDate triggerServiceDate);

    /** Cooldown check for a recurring (not one-shot) reminder — see
     * {@code ColorBoosterReminderScheduler}: a customer who was actually sent this automation
     * within the cooldown window isn't reconsidered, but one who was only ever skipped (no phone,
     * negative feedback, already booked) is re-evaluated fresh on every run — those facts can
     * change day to day. */
    boolean existsByBusinessIdAndAutomationKeyAndSquareCustomerIdAndStateAndCreatedAtAfter(
            Long businessId, String automationKey, String squareCustomerId, String state, Instant createdAtAfter);
}
