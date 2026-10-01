package com.salonreview.telegram;

import java.util.List;

/** Payload posted by akluxnails-home's standalone card-on-file page (akluxnails.com/card, owner
 * request 2026-10-01) to {@code POST /api/internal/notifications/card-on-file}, so staff see every
 * card saved there, every card the bank/Square refused, and a possible card-testing attack.
 * Never carries the card number: only what Square itself returns about a saved card (brand,
 * last 4, type, expiry). {@code businessId}/{@code businessShortCode} follow the same
 * both-nullable, businessId-wins convention as every other request record in
 * {@link com.salonreview.web.InternalNotificationController}. */
public record CardOnFileNotification(
        Long businessId,
        String businessShortCode,
        Event event,
        String customerName,     // as typed on the form
        String phoneNumber,
        String email,            // nullable
        String cardBrand,        // nullable (SAVED only), e.g. "VISA"
        String last4,            // nullable (SAVED only)
        String cardType,         // nullable, CREDIT / DEBIT
        String expiry,           // nullable, "MM/YYYY"
        String errorCode,        // nullable (DECLINED only), raw Square code, e.g. CVV_FAILURE
        String errorMessage,     // nullable, what the client was shown
        List<String> warnings,   // nullable: prepaid card, name mismatch, expires soon, ...
        Integer failedAttempts   // nullable: failed tries for this phone in the last hour
) {
    public enum Event {
        /** Card saved on the customer's Square profile. */
        SAVED,
        /** Square or the bank refused the card (declined, CVV/ZIP mismatch, invalid number...). */
        DECLINED,
        /** Too many failures site-wide in a short time: form paused, possible card testing. */
        BLOCKED
    }
}
