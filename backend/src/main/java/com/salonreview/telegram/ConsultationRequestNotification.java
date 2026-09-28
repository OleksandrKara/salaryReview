package com.salonreview.telegram;

/** Payload posted by salonLandings the moment a customer books a free PMU consultation — see
 * {@code PmuBookingService.book_consultation()}. Fires alongside (not instead of) the customer's
 * own {@code consultation_request_confirmation} SMS — until this existed, staff had no visibility
 * that a consultation had even been booked; this alert is purely for that visibility, same "staff
 * heads-up, not a customer-facing message" role as {@link PaymentFailedNotification}'s Telegram
 * leg. {@code businessId}/{@code businessShortCode} follow the same both-nullable, businessId-wins
 * convention as every other request record in {@link com.salonreview.web.InternalNotificationController}. */
public record ConsultationRequestNotification(
        Long businessId,
        String businessShortCode,
        String customerName,     // nullable — best-effort, whatever the form had at submit time
        String phoneNumber,      // nullable
        String startAt,          // ISO-8601 UTC instant — the booked appointment's start time
        boolean online,          // true: phone-call consultation. false: in-person, at locationAddress
        String locationAddress,  // nullable — only meaningful (and only ever populated) when !online
        String artistName        // nullable — the booked team member's display name (2026-09-28 owner request)
) {
}
