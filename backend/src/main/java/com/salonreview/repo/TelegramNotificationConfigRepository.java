package com.salonreview.repo;

import com.salonreview.domain.TelegramNotificationConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TelegramNotificationConfigRepository extends JpaRepository<TelegramNotificationConfig, Boolean> {

    /** The single config row, seeded by V45. */
    default TelegramNotificationConfig getSingleton() {
        return findById(Boolean.TRUE)
                .orElseThrow(() -> new IllegalStateException("telegram_notification_config seed row missing — V45?"));
    }
}
