package com.salonreview.repo;

import com.salonreview.domain.ProviderScheduleClosureAlertConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProviderScheduleClosureAlertConfigRepository extends JpaRepository<ProviderScheduleClosureAlertConfig, Long> {

    Optional<ProviderScheduleClosureAlertConfig> findByBusinessId(Long businessId);
}
