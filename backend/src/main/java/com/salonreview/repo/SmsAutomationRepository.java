package com.salonreview.repo;

import com.salonreview.domain.SmsAutomation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SmsAutomationRepository extends JpaRepository<SmsAutomation, String> {
}
