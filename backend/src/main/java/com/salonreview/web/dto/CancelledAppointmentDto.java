package com.salonreview.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * One cancelled appointment row for the /reports/{providerId}/cancellations owner-review page. These
 * are appointments the salon marked CANCELLED_BY_SELLER, assigned to a provider (owner/manager staff
 * excluded). {@code cleared} is true once the owner has reviewed it (checked cameras) — included so
 * the page can show "Cleared earlier" with an Undo button without a second query. {@code gross} is the
 * catalog price the appointment would have been worth, null when it didn't resolve.
 */
public record CancelledAppointmentDto(
        String bookingId,
        String date,                 // yyyy-MM-dd, salon-local
        String time,                 // h:mm a, salon-local
        String customerId,
        String customerName,         // best-effort, nullable
        String serviceName,          // best-effort, nullable; joined with " + " across segments
        BigDecimal gross,            // nullable; summed across segments
        List<ServiceLineDto> services,
        String half,                 // "FIRST" / "SECOND"
        String sellerNote,           // seller-side note on the appointment, nullable
        String customerNote,         // customer-side note on the appointment, nullable
        boolean cleared,
        String clearedBy,            // nullable when cleared=false
        Instant clearedAt,           // nullable when cleared=false
        String clearedNote           // nullable when cleared=false or no note
) {}
