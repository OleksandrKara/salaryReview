package com.salonreview.square.webhook;

import com.salonreview.square.SquareBookingMirrorIngestService;
import com.salonreview.square.SquareClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Mirrors a {@code payment.created}/{@code payment.updated} webhook event into {@code
 * square_payment} — see {@link SquareWebhookEvent.Payment}'s own doc for why no extra Square call
 * is needed here (the full payment is already inline in the payload, unlike {@code order.updated}).
 * Independent of, and never entangled with, {@code CheckoutReviewTriggerService
 * #handlePaymentUpdated}'s own listener on the same event — same pattern as {@code
 * SquareBookingWebhookHandler}/{@code SquareOrderWebhookHandler} being independent of each other.
 * Best-effort: a failure here never fails the webhook response, same reasoning as those two
 * (Square would just retry-storm an endpoint that 500s); the reconciliation sweep catches anything
 * missed.
 */
@Service
public class SquarePaymentWebhookHandler {

    private static final Logger log = LoggerFactory.getLogger(SquarePaymentWebhookHandler.class);

    private final SquareBookingMirrorIngestService ingest;

    public SquarePaymentWebhookHandler(SquareBookingMirrorIngestService ingest) {
        this.ingest = ingest;
    }

    public void handlePaymentEvent(Long businessId, SquareWebhookEvent.Payment payment) {
        if (payment == null || payment.id() == null) return;
        try {
            SquareClient.Payment mapped = new SquareClient.Payment(payment.id(), payment.orderId(),
                    payment.customerId(), payment.status(), payment.createdAt(),
                    payment.amountMoney(), payment.tipMoney());
            ingest.upsertPayment(businessId, mapped);
        } catch (RuntimeException ex) {
            log.warn("Failed to mirror payment {} for business {} from webhook (reconciliation will "
                    + "catch it): {}", payment.id(), businessId, ex.toString());
        }
    }
}
