package com.salonreview.web.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One suspicious booking row for the /reports/{providerId}/suspicious detail page. {@code cleared}
 * is true when the owner/manager has already cleared this booking — included so the detail page can
 * show "Cleared earlier" with an Undo button without a second query. {@code gross} is null when the
 * catalog price for the service variation didn't resolve.
 */
public record SuspiciousBookingDto(
        String bookingId,
        String date,                 // yyyy-MM-dd, salon-local
        String time,                 // h:mm a, salon-local
        String customerId,
        String customerName,         // best-effort, nullable
        String serviceName,          // best-effort, nullable
        BigDecimal gross,            // nullable
        String half,                 // "FIRST" / "SECOND"
        /** Seller-side note on the appointment (the salon's internal note), nullable. */
        String sellerNote,
        /** Customer-side note on the appointment, nullable. */
        String customerNote,
        boolean cleared,
        String clearedBy,            // nullable when cleared=false
        Instant clearedAt,           // nullable when cleared=false
        String clearedNote           // nullable when cleared=false or no note
) {}
