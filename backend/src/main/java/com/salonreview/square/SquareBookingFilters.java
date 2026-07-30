package com.salonreview.square;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/**
 * Shared classification of a {@link SquareClient.Booking}'s real-world status — "did it actually
 * happen" and "is it today or later" — used wherever more than one caller needs to answer the same
 * question consistently. Originally lived only in {@code MarketingAnalyticsService}'s
 * upcoming/cancelled-appointment lists; extracted here so
 * {@code LeadFollowUpScheduler} (see openspec/changes/lead-followup-and-manager-inbox design.md
 * D2) can reuse the exact same rules rather than re-deriving them.
 */
public final class SquareBookingFilters {

    private SquareBookingFilters() {
    }

    public static boolean didHappen(SquareClient.Booking b) {
        String status = b.status();
        if (status == null) return true;
        return switch (status) {
            case "CANCELLED_BY_CUSTOMER", "CANCELLED_BY_SELLER", "DECLINED", "NO_SHOW" -> false;
            default -> true;
        };
    }

    public static boolean isTodayOrLater(String startAt, LocalDate today) {
        if (startAt == null || startAt.isBlank()) return false;
        try {
            LocalDate day = Instant.parse(startAt).atZone(ZoneOffset.UTC).toLocalDate();
            return !day.isBefore(today);
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}
