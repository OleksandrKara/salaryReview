package com.salonreview.square.webhook;

import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareCustomerMirrorIngestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Mirrors a {@code customer.created}/{@code customer.updated} webhook event into {@code
 * square_customer} — see {@link SquareWebhookEvent.Customer}'s own doc for why no extra Square
 * call is needed here (the full customer is already inline in the payload, unlike {@code
 * order.updated}). Best-effort: a failure here never fails the webhook response, same reasoning as
 * {@code SquareBookingWebhookHandler}/{@code SquareOrderWebhookHandler}/{@code
 * SquarePaymentWebhookHandler} — Square would just retry-storm an endpoint that 500s; the periodic
 * full re-sync catches anything missed.
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
}
