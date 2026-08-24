package com.salonreview.repo;

import com.salonreview.domain.ServiceLifecycleReminderSend;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;

public interface ServiceLifecycleReminderSendRepository extends JpaRepository<ServiceLifecycleReminderSend, Long> {
    boolean existsByBusinessIdAndAutomationKeyAndSquareCustomerIdAndTriggerServiceDate(
            Long businessId, String automationKey, String squareCustomerId, LocalDate triggerServiceDate);
}
