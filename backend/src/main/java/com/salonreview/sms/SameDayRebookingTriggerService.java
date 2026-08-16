package com.salonreview.sms;

import com.salonreview.domain.SameDayRebookingSend;
import com.salonreview.repo.SameDayRebookingSendRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Turns the same qualifying Square {@code payment.updated} event
 * {@code CheckoutReviewTriggerService} already enqueues off of into a pending
 * {@code same_day_rebooking_discount} send — see openspec/changes/same-day-rebooking-discount
 * design.md D1/D2. Called directly from {@code CheckoutReviewTriggerService.handlePaymentUpdated}
 * with already-resolved values (no duplicate Square lookups). Never throws back to the webhook
 * controller — matches the "never block, never throw" convention every notifier in this codebase
 * follows.
 *
 * <p>{@code businessId} is passed in by the caller, which today always resolves it via
 * {@code BusinessRepository#legacySmsBusiness} (the webhook handler is still Phase-3.6-blocked —
 * see tasks.md 3.7) — this service itself is business-id-correct regardless of how the caller got
 * there, so it needs no changes once that upstream gap closes.
 */
@Service
public class SameDayRebookingTriggerService {

    private static final Logger log = LoggerFactory.getLogger(SameDayRebookingTriggerService.class);
    static final Duration SEND_DELAY = Duration.ofHours(3);
    /** DST-safe — always resolves to the salon's real local midnight, not a fixed UTC offset. */
    private static final ZoneId SALON_ZONE = ZoneId.of("America/Los_Angeles");

    private final SameDayRebookingSendRepository repository;

    public SameDayRebookingTriggerService(SameDayRebookingSendRepository repository) {
        this.repository = repository;
    }

    public void enqueue(Long businessId, String paymentId, String customerId, String phoneNumber, String customerName) {
        try {
            if (repository.existsBySquarePaymentId(paymentId)) {
                return; // Square redelivered an event we already enqueued a send for
            }
            Instant now = Instant.now();
            // Midnight at the *start* of tomorrow, salon-local — i.e. the end of the calendar day
            // the payment completed on (see design.md D2).
            Instant promoExpiresAt = ZonedDateTime.now(SALON_ZONE).toLocalDate().plusDays(1)
                    .atStartOfDay(SALON_ZONE).toInstant();

            repository.save(SameDayRebookingSend.builder()
                    .businessId(businessId)
                    .phoneNumber(phoneNumber)
                    .customerName(customerName)
                    .squareCustomerId(customerId)
                    .squarePaymentId(paymentId)
                    .sendDueAt(now.plus(SEND_DELAY))
                    .promoExpiresAt(promoExpiresAt)
                    .state(SameDayRebookingSend.STATE_AWAITING_SEND)
                    .build());
        } catch (Exception e) {
            log.warn("Same-day-rebooking trigger failed for payment {} (event ignored): {}", paymentId, e.getMessage());
        }
    }
}
