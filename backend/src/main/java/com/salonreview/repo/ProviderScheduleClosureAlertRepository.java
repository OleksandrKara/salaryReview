package com.salonreview.repo;

import com.salonreview.domain.ProviderScheduleClosureAlert;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface ProviderScheduleClosureAlertRepository extends JpaRepository<ProviderScheduleClosureAlert, Long> {

    long countByBusinessIdAndSentAtAfter(Long businessId, Instant since);
}
