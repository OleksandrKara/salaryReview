package com.salonreview.square.webhook;

import com.salonreview.square.SquareBookingMirrorIngestService;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClientProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Mirrors an {@code order.updated} webhook event into {@code square_order}. Unlike bookings,
 * Square's own {@code order.updated} payload is a summary only (id/state/version, no line items or
 * tenders — see {@link SquareWebhookEvent.OrderUpdated}'s own doc), so this fetches the full order
 * via {@link SquareClient#orderById} first, the same pattern {@code CheckoutReviewTriggerService
 * #handlePaymentUpdated} already uses for {@code payment.updated}. Best-effort, same reasoning as
 * {@link SquareBookingWebhookHandler}.
 */
@Service
public class SquareOrderWebhookHandler {

    private static final Logger log = LoggerFactory.getLogger(SquareOrderWebhookHandler.class);

    private final SquareClientProvider squareClientProvider;
    private final SquareBookingMirrorIngestService ingest;

    public SquareOrderWebhookHandler(SquareClientProvider squareClientProvider, SquareBookingMirrorIngestService ingest) {
        this.squareClientProvider = squareClientProvider;
        this.ingest = ingest;
    }

    public void handleOrderUpdated(Long businessId, SquareWebhookEvent.OrderUpdated orderUpdated) {
        if (orderUpdated == null || orderUpdated.orderId() == null) return;
        try {
            SquareClient square = squareClientProvider.forBusiness(businessId);
            Optional<SquareClient.Order> order = square.orderById(orderUpdated.orderId());
            if (order.isEmpty()) {
                log.warn("Square order {} not found for business {} while mirroring order.updated",
                        orderUpdated.orderId(), businessId);
                return;
            }
            ingest.upsertOrder(businessId, order.get());
        } catch (RuntimeException ex) {
            log.warn("Failed to mirror order {} for business {} from webhook (reconciliation will "
                    + "catch it): {}", orderUpdated.orderId(), businessId, ex.toString());
        }
    }
}
