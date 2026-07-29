package com.salonreview.square.webhook;

import com.salonreview.domain.SmsReplyFlow;
import com.salonreview.repo.SmsReplyFlowRepository;
import com.salonreview.square.SquareClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Turns a qualifying Square {@code payment.updated} event into a pending
 * {@code checkout_review_request} flow — see openspec/changes/sms-automations-hub design.md
 * D1/D2. Never throws back to the webhook controller: any failure here is logged and the event is
 * simply not actioned, matching this codebase's "never block, never throw" notifier convention.
 */
@Service
public class CheckoutReviewTriggerService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutReviewTriggerService.class);
    static final String AUTOMATION_KEY = "checkout_review_request";
    static final Duration SEND_DELAY = Duration.ofMinutes(2);

    private final SquareClient square;
    private final SmsReplyFlowRepository repository;

    public CheckoutReviewTriggerService(SquareClient square, SmsReplyFlowRepository repository) {
        this.square = square;
        this.repository = repository;
    }

    public void handlePaymentUpdated(SquareWebhookEvent.Payment payment) {
        try {
            if (payment == null || !"COMPLETED".equals(payment.status()) || payment.orderId() == null) {
                return;
            }
            if (repository.existsBySquarePaymentId(payment.id())) {
                return; // Square redelivered an event we already enqueued a flow for
            }

            Optional<SquareClient.Order> order = square.orderById(payment.orderId());
            if (order.isEmpty()) {
                log.warn("Checkout-review trigger: order {} not found for payment {}", payment.orderId(), payment.id());
                return;
            }
            if (SquareClient.isBookingLinked(order.get())) {
                return; // online-booking payment — not an in-salon checkout, see design.md D2
            }

            String customerId = order.get().customerId();
            if (customerId == null) {
                return; // no customer on the order at all — nothing to text
            }
            String phoneNumber = square.customerPhone(customerId);
            if (phoneNumber == null) {
                return; // genuinely anonymous walk-in with no phone on file — silent skip, see D2
            }
            String customerName = square.customerNames(List.of(customerId)).get(customerId);

            repository.save(SmsReplyFlow.builder()
                    .automationKey(AUTOMATION_KEY)
                    .phoneNumber(phoneNumber)
                    .customerName(customerName)
                    .state(SmsReplyFlow.STATE_AWAITING_SEND)
                    .squarePaymentId(payment.id())
                    .sendDueAt(Instant.now().plus(SEND_DELAY))
                    .build());
        } catch (Exception e) {
            log.warn("Checkout-review trigger failed for payment {} (event ignored): {}",
                    payment == null ? null : payment.id(), e.getMessage());
        }
    }
}
