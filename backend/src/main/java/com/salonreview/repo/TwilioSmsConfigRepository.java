package com.salonreview.repo;

import com.salonreview.domain.TwilioSmsConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TwilioSmsConfigRepository extends JpaRepository<TwilioSmsConfig, Long> {

    Optional<TwilioSmsConfig> findByBusinessId(Long businessId);
}
