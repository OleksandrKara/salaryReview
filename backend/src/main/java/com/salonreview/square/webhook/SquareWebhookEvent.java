package com.salonreview.square.webhook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Square's webhook envelope — only the fields the checkout-review-request automation needs;
 * unknown JSON is ignored so Square API version drift doesn't break deserialization (same
 * convention as {@code SquareClient}'s own response models).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SquareWebhookEvent(String type, String eventId, Data data) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Data(String type, String id, DataObject object) {

        /** Named {@code DataObject}, not {@code Object} — the JSON field is still {@code "object"}
         * (Jackson maps by the record component name), this just avoids shadowing
         * {@code java.lang.Object} inside this file. */
        @JsonIgnoreProperties(ignoreUnknown = true)
        @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
        public record DataObject(Payment payment) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Payment(String id, String status, String orderId, String customerId) {}
}
