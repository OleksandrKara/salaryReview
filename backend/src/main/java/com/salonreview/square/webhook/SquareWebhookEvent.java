package com.salonreview.square.webhook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.salonreview.square.SquareClient;

import java.util.List;

/**
 * Square's webhook envelope — only the fields the checkout-review-request automation and the
 * Phase 1 Square-data mirror (see {@code SquareBookingWebhookHandler}/{@code
 * SquareOrderWebhookHandler}) need; unknown JSON is ignored so Square API version drift doesn't
 * break deserialization (same convention as {@code SquareClient}'s own response models).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SquareWebhookEvent(String type, String eventId, Data data) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Data(String type, String id, DataObject object) {

        /** Named {@code DataObject}, not {@code Object} — the JSON field is still {@code "object"}
         * (Jackson maps by the record component name), this just avoids shadowing
         * {@code java.lang.Object} inside this file. {@code booking.created}/{@code
         * booking.updated} nest the full booking under {@code "booking"}; {@code order.updated},
         * {@code order.created}, and {@code order.fulfillment.updated} each nest only a summary,
         * under {@code "order_updated"}/{@code "order_created"}/{@code "order_fulfillment_updated"}
         * respectively (Square's own inconsistency between the two APIs, and between its own order
         * event types — confirmed against Square's published webhook reference, not guessed). All
         * three summaries share the one field the mirror actually needs ({@code order_id}), so they
         * reuse {@link OrderUpdated}'s shape rather than three near-identical records. */
        @JsonIgnoreProperties(ignoreUnknown = true)
        @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
        public record DataObject(Payment payment, Booking booking, OrderUpdated orderUpdated,
                                  OrderUpdated orderCreated, OrderUpdated orderFulfillmentUpdated,
                                  Customer customer) {}
    }

    /** {@code payment.created}/{@code payment.updated} carry the full payment object inline in the
     * webhook payload (confirmed against Square's published webhook reference, not guessed) — no
     * follow-up Square call is needed to mirror it, same as {@link Booking}. {@code createdAt}/
     * {@code amountMoney}/{@code tipMoney} exist only for the Phase 2 payment-mirror handler;
     * {@code CheckoutReviewTriggerService#handlePaymentUpdated} only ever reads {@code
     * id}/{@code status}/{@code orderId}. {@code SquareClient.Money} is reused as-is (no field-order
     * mismatch), same reasoning as {@link Booking}'s reuse of {@code AppointmentSegment}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Payment(String id, String status, String orderId, String customerId, String createdAt,
                          SquareClient.Money amountMoney, SquareClient.Money tipMoney) {}

    /** The FULL booking object, inline in the webhook payload itself — unlike {@link
     * OrderUpdated}, no follow-up Square call is needed to mirror a booking event. Same field
     * shape as {@code SquareClient.Booking}, kept separate (not reused directly) only because that
     * one's field order/constructor doesn't match this envelope's JSON shape closely enough to
     * share cleanly — {@code AppointmentSegment} itself, with no such mismatch, is reused as-is. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Booking(String id, String status, String customerId, String startAt, String createdAt,
                          String updatedAt, String locationId, String sellerNote, String customerNote,
                          List<SquareClient.AppointmentSegment> appointmentSegments) {}

    /** {@code order.updated}'s payload is a summary only (id/state/version/timestamps, no line
     * items or tenders) — mirroring an order event means fetching the full order via {@link
     * SquareClient#orderById}, the same pattern {@code CheckoutReviewTriggerService
     * #handlePaymentUpdated} already uses for {@code payment.updated}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record OrderUpdated(String orderId, String state) {}

    /** {@code customer.created}/{@code customer.updated} carry the FULL customer object inline in
     * the webhook payload (confirmed against Square's own published webhook reference — a sample
     * payload showing {@code data.object.customer} with address/birthday/created_at/email_address/
     * family_name/given_name/phone_number/preferences all present, not guessed) — no follow-up
     * Square call is needed to mirror a customer event, same as {@link Booking}/{@link Payment}.
     * Only the fields {@code SquareCustomerMirrorIngestService} actually stores are mapped here.
     * Square's merge behavior for the *losing* side of a merge isn't clearly documented for this
     * webhook (their own docs point at {@code customer.created} for merge-affected profiles, not
     * {@code customer.updated}) — the ingest path runs every incoming id through the existing
     * {@code SquareClient#canonicalCustomerIds} before upserting as a safety net, so a stale/losing
     * id never gets stored as if it were still current. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Customer(String id, String givenName, String familyName, String emailAddress,
                           String phoneNumber, String createdAt) {}
}
