package com.salonreview.repo;

import com.salonreview.domain.TelegramNotificationConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TelegramNotificationConfigRepository extends JpaRepository<TelegramNotificationConfig, Long> {

    Optional<TelegramNotificationConfig> findByBusinessId(Long businessId);
}
