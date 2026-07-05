package com.salonreview.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record MarketingContactHistoryDto(
        /** Every form submission (step1 lead capture, booking, four_hand_request) this contact's
         * phone/email ever made, most recent first — the multi-touch journey across visits. */
        List<Submission> submissions,
        /** This contact's Square appointment history, most recent/upcoming first. Empty (not
         * null) when the contact has no known Square customer, or Square has no bookings for
         * them yet — the frontend doesn't need to distinguish those two cases.
         */
        List<Appointment> appointments
) {
    public record Submission(
            String submissionType,
            Instant occurredAt,
            String landingPageSlug,
            String variantName,
            String utmSource,
            String utmMedium,
            String utmCampaign,
            String serviceName,
            BigDecimal price
    ) {}

    public record Appointment(
            String bookingId,
            String status,
            Instant startAt,
            String serviceName,
            /** Current catalog list price — Square's Bookings API doesn't retain what was
             * actually charged, so this is a best-effort estimate, not a payroll figure. */
            BigDecimal price,
            String artistName
    ) {}
}
