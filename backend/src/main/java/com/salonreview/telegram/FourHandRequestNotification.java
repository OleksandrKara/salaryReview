package com.salonreview.telegram;

/** Payload posted by mani/akluxnails-home to {@code POST /api/internal/notifications/four-hand-request}.
 * {@code businessShortCode}/{@code businessId} are both nullable — resolved by
 * {@code InternalNotificationController#resolveBusiness}, same both-optional convention as {@code
 * RebookingPromoEnrollRequest} (both blank/null defaults to Business A, backward-compatible with
 * mani/akluxnails-home's existing callers, which today send neither). */
public record FourHandRequestNotification(
        String source,              // "mani" | "akluxnails-home"
        String customerName,
        String phoneNumber,
        String requestedServices,   // nullable
        String preferredStartAt,    // ISO 8601, as picked in the slot UI
        String note,                // nullable
        Double estimatedPrice,      // nullable — marketing display estimate in dollars, not a real Square price
        String businessShortCode,   // nullable
        Long businessId             // nullable — wins over businessShortCode when both are present
) {
}
