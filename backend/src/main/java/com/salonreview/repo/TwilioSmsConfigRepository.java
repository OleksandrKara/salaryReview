package com.salonreview.repo;

import com.salonreview.domain.TwilioSmsConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TwilioSmsConfigRepository extends JpaRepository<TwilioSmsConfig, Long> {

    Optional<TwilioSmsConfig> findByBusinessId(Long businessId);

    /** Resolves which business owns a Twilio number that just received an inbound SMS — the
     * public inbound webhook's "To" field is the salon's own number, matched here since it's now
     * business-scoped. See {@code TwilioInboundSmsController}. */
    Optional<TwilioSmsConfig> findByFromPhoneNumber(String fromPhoneNumber);
}
