package com.salonreview.square.webhook;

import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareCustomerMirrorIngestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Mirrors a {@code customer.created}/{@code customer.updated}/{@code customer.deleted} webhook
 * event into {@code square_customer} — see {@link SquareWebhookEvent.Customer}'s own doc for why
 * no extra Square call is needed here (the full customer is already inline in the payload for all
 * three event types, unlike {@code order.updated}). All three carry the identical {@code
 * data.object.customer} shape (confirmed against Square's own published {@code customer.deleted}
 * reference — same fields as created/updated, minus {@code group_ids}/{@code segment_ids}, neither
 * of which this mirror stores anyway), so the *type string itself* is the only way to tell a
 * deletion from an upsert — {@link SquareWebhookController} passes it through explicitly for this
 * handler alone, unlike booking/order/payment, which dispatch purely on which payload field is
 * populated. Best-effort: a failure here never fails the webhook response, same reasoning as
 * {@code SquareBookingWebhookHandler}/{@code SquareOrderWebhookHandler}/{@code
 * SquarePaymentWebhookHandler} — Square would just retry-storm an endpoint that 500s; the periodic
 * full re-sync catches anything missed (except a deletion — see {@code
 * SquareCustomerMirrorIngestService#deleteCustomer}'s own doc on why that one only ever arrives via
 * this webhook).
 */
@Service
public class SquareCustomerWebhookHandler {

    private static final Logger log = LoggerFactory.getLogger(SquareCustomerWebhookHandler.class);

    private final SquareCustomerMirrorIngestService ingest;

    public SquareCustomerWebhookHandler(SquareCustomerMirrorIngestService ingest) {
        this.ingest = ingest;
    }

    public void handleCustomerEvent(Long businessId, SquareWebhookEvent.Customer customer) {
        if (customer == null || customer.id() == null) return;
        try {
            SquareClient.Customer mapped = new SquareClient.Customer(customer.id(), customer.givenName(),
                    customer.familyName(), customer.createdAt(), customer.phoneNumber(),
                    customer.emailAddress(), null);
            ingest.upsertCustomer(businessId, mapped);
        } catch (RuntimeException ex) {
            log.warn("Failed to mirror customer {} for business {} from webhook (next full sync will "
                    + "catch it): {}", customer.id(), businessId, ex.toString());
        }
    }

    /** {@code customer.deleted} — see this class's own doc for why this needs its own method
     * rather than a flag on {@link #handleCustomerEvent}: the payload shape is identical to
     * created/updated, so the caller ({@link SquareWebhookController}) is the one place that still
     * knows which event type this actually was. */
    public void handleCustomerDeleted(Long businessId, SquareWebhookEvent.Customer customer) {
        if (customer == null || customer.id() == null) return;
        try {
            ingest.deleteCustomer(businessId, customer.id());
        } catch (RuntimeException ex) {
            log.warn("Failed to remove deleted customer {} for business {} from webhook: {}",
                    customer.id(), businessId, ex.toString());
        }
    }
}
