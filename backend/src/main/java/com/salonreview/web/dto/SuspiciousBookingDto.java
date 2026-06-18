package com.salonreview.web.dto;

import com.salonreview.ai.TriageResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * One suspicious booking row for the /reports/{providerId}/suspicious detail page. {@code cleared}
 * is true when the owner/manager has already cleared this booking — included so the detail page can
 * show "Cleared earlier" with an Undo button without a second query. {@code gross} is null when the
 * catalog price for the service variation didn't resolve.
 *
 * <p>{@code triage} is the cached AI triage result under the current prompt version when one exists
 * — included so the page can render the AI explanation on initial load without N+1 round trips.
 * Null when no triage exists yet, when the feature is off, or when the cached row is from an older
 * prompt version.
 */
public record SuspiciousBookingDto(
        String bookingId,
        String date,                 // yyyy-MM-dd, salon-local
        String time,                 // h:mm a, salon-local
        String customerId,
        String customerName,         // best-effort, nullable
        String serviceName,          // best-effort, nullable; joined with " + " across segments
        BigDecimal gross,            // nullable; summed across segments
        /**
         * Per-segment service breakdown — one entry per service variation on the booking. The
         * detail page renders these as chips alongside the combined {@link #serviceName} for
         * easier scanning of multi-service appointments. Empty (not null) when no services
         * resolved.
         */
        List<ServiceLineDto> services,
        String half,                 // "FIRST" / "SECOND"
        /** Seller-side note on the appointment (the salon's internal note), nullable. */
        String sellerNote,
        /** Customer-side note on the appointment, nullable. */
        String customerNote,
        boolean cleared,
        String clearedBy,            // nullable when cleared=false
        Instant clearedAt,           // nullable when cleared=false
        String clearedNote,          // nullable when cleared=false or no note
        TriageResult triage          // nullable; cached AI triage under current prompt version
) {}
