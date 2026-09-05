package com.salonreview.telegram;

/** Payload posted by any business's landing page to
 * {@code POST /api/internal/notifications/payment-failed} the moment a customer-entered card
 * fails to charge — whether a genuine decline (their card/bank said no) or a failure on our own
 * side (Square API error, bad request, etc.). {@code businessId}/{@code businessShortCode} follow
 * the same both-nullable, businessId-wins convention as every other request record in
 * {@link com.salonreview.web.InternalNotificationController}. */
public record PaymentFailedNotification(
        Long businessId,
        String businessShortCode,
        String customerName,     // nullable — best-effort, whatever the form had at submit time
        String phoneNumber,      // nullable
        String serviceName,      // e.g. "Touch-Up" — whatever the caller was trying to charge for
        Double amount,           // dollars attempted, nullable if the caller couldn't resolve it
        String errorMessage,     // human-readable reason shown to (or logged for) the customer
        String errorCode,        // nullable — raw Square error code (CARD_DECLINED, etc.) if any
        boolean clientError      // true: their card/bank said no. false: failure on our own side.
) {
}
