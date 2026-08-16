package com.salonreview.repo;

import com.salonreview.domain.SmsReplyFlow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SmsReplyFlowRepository extends JpaRepository<SmsReplyFlow, Long> {

    /** Square may redeliver the same webhook event — this makes enqueueing idempotent. */
    boolean existsBySquarePaymentId(String squarePaymentId);

    /** Rows the {@code SmsReplyFlowScheduler} should send now, for one business. */
    List<SmsReplyFlow> findByBusinessIdAndStateAndSendDueAtBefore(Long businessId, String state, Instant now);

    /** Rows whose 24h reply window has lapsed with no reply, for one business. */
    List<SmsReplyFlow> findByBusinessIdAndStateAndReplyExpiresAtBefore(Long businessId, String state, Instant now);

    /** The one flow an inbound reply from this number could possibly be answering — see
     * design.md D4 ("newest pending row for this phone number" is correct at this salon's
     * scale). Scoped to one business since phone_number alone carries no business signal. */
    Optional<SmsReplyFlow> findFirstByBusinessIdAndPhoneNumberAndStateOrderByCreatedAtDesc(
            Long businessId, String phoneNumber, String state);

    /** Single-flow lookup scoped to a business — the owner-side manual-recovery ownership check
     * (see {@code CheckoutReviewFlowRecoveryService}), so a flow id from another business's table
     * 404s instead of being retriable cross-tenant. */
    Optional<SmsReplyFlow> findByIdAndBusinessId(Long id, Long businessId);
}
