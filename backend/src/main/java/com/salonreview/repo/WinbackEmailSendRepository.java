package com.salonreview.repo;

import com.salonreview.domain.WinbackEmailSend;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WinbackEmailSendRepository extends JpaRepository<WinbackEmailSend, Long> {

    /** Idempotency check — one row per {@code sms_message.id}, ever. See
     * {@code WinbackEmailFallbackScheduler}. */
    boolean existsBySmsMessageId(Long smsMessageId);
}
