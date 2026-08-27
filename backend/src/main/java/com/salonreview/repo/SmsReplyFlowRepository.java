package com.salonreview.repo;

import com.salonreview.domain.SmsReplyFlow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SmsReplyFlowRepository extends JpaRepository<SmsReplyFlow, Long> {

    /** Square may redeliver the same webhook event — this makes enqueueing idempotent. */
    boolean existsBySquarePaymentId(String squarePaymentId);

    /** Whether this phone number already got a {@code checkout_review_request} today — found live
     * 2026-08-27: two family members checked out on separate Square payments (different
     * {@code square_payment_id}, same phone number on the account) within a minute of each other,
     * and the payment-id dedup above correctly treated them as two distinct real events, so the
     * customer got the same "How'd your visit with {tech} go?" text twice. One ask per phone
     * number per day is enough regardless of how many separate payments that visit produced — see
     * {@code CheckoutReviewTriggerService}. */
    boolean existsByBusinessIdAndPhoneNumberAndAutomationKeyAndCreatedAtAfter(
            Long businessId, String phoneNumber, String automationKey, Instant after);

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

    /** Rows V120's one-time startup backfill still needs to resolve a provider for — a customer
     * to look bookings up for is the one hard requirement; already-resolved rows (including a
     * previous backfill run's own "genuinely unresolvable" misses, which stay {@code null}
     * forever) are skipped on every later restart since this can't tell "never tried" apart from
     * "tried and found nothing" — see {@code CheckoutReviewProviderRatingBackfillStartup}'s own
     * doc for why that's an acceptable one-time-best-effort tradeoff. */
    List<SmsReplyFlow> findByBusinessIdAndAutomationKeyAndProviderIdIsNullAndSquareCustomerIdIsNotNull(
            Long businessId, String automationKey);

    /** The flow a historical (pre-V120) inbound reply most likely answered, for the same backfill
     * — the newest flow for this phone number/automation that existed before the reply arrived.
     * Not state-scoped (unlike {@link #findFirstByBusinessIdAndPhoneNumberAndStateOrderByCreatedAtDesc},
     * used for live matching): a backfilled flow's state has already since moved past
     * {@code AWAITING_REPLY} to {@code COMPLETED}, so matching on state here would never find it. */
    Optional<SmsReplyFlow> findFirstByBusinessIdAndPhoneNumberAndAutomationKeyAndCreatedAtBeforeOrderByCreatedAtDesc(
            Long businessId, String phoneNumber, String automationKey, Instant before);
}
