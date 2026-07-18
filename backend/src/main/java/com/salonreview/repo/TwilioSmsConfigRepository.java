package com.salonreview.repo;

import com.salonreview.domain.TwilioSmsConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TwilioSmsConfigRepository extends JpaRepository<TwilioSmsConfig, Boolean> {

    /** The single config row, seeded by V46. */
    default TwilioSmsConfig getSingleton() {
        return findById(Boolean.TRUE)
                .orElseThrow(() -> new IllegalStateException("twilio_sms_config seed row missing — V46?"));
    }
}
