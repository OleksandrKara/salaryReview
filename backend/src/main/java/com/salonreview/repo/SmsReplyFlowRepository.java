package com.salonreview.repo;

import com.salonreview.domain.SmsReplyFlow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SmsReplyFlowRepository extends JpaRepository<SmsReplyFlow, Long> {

    /** Square may redeliver the same webhook event — this makes enqueueing idempotent. */
    boolean existsBySquarePaymentId(String squarePaymentId);

    /** Rows the {@code SmsReplyFlowScheduler} should send now. */
    List<SmsReplyFlow> findByStateAndSendDueAtBefore(String state, Instant now);

    /** Rows whose 24h reply window has lapsed with no reply. */
    List<SmsReplyFlow> findByStateAndReplyExpiresAtBefore(String state, Instant now);

    /** The one flow an inbound reply from this number could possibly be answering — see
     * design.md D4 ("newest pending row for this phone number" is correct at this salon's scale). */
    Optional<SmsReplyFlow> findFirstByPhoneNumberAndStateOrderByCreatedAtDesc(String phoneNumber, String state);
}
