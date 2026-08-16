package com.salonreview.repo;

import com.salonreview.domain.SmsAutomation;
import com.salonreview.domain.SmsAutomationId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SmsAutomationRepository extends JpaRepository<SmsAutomation, SmsAutomationId> {

    Optional<SmsAutomation> findByBusinessIdAndAutomationKey(Long businessId, String automationKey);
}
