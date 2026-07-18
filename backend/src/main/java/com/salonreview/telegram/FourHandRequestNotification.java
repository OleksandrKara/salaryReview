package com.salonreview.telegram;

/** Payload posted by mani/akluxnails-home to {@code POST /api/internal/notifications/four-hand-request}. */
public record FourHandRequestNotification(
        String source,              // "mani" | "akluxnails-home"
        String customerName,
        String phoneNumber,
        String requestedServices,   // nullable
        String preferredStartAt,    // ISO 8601, as picked in the slot UI
        String note                 // nullable
) {
}
