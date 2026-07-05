package com.salonreview.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record MarketingContactDto(
        boolean available,
        List<Contact> contacts
) {
    public record Contact(
            String id,
            String givenName,
            String phoneNumber,
            String emailAddress,
            String originalTrafficSource,
            String marketingTrafficSource,
            /** Landing page + variant the lead first saw — denormalized at capture time, so a
             * later rename/delete of the variant never changes what this record says. */
            String landingPageSlug,
            String variantName,
            /** Most recent visit's device/OS/browser. */
            String deviceType,
            String osName,
            String osVersion,
            String browserName,
            String browserVersion,
            Boolean smsMarketingConsent,
            Boolean emailMarketingConsent,
            /** Square Dashboard customer profile link, or null if no Square customer is known
             * for this contact yet (neither found by lookup nor created by a booking). */
            String squareProfileUrl,
            /** Every form submission (step1 lead capture, booking, four_hand_request) this
             * contact's phone/email ever made, most recent first. Always fetched (cheap — our
             * own DB), so the UI can show "no submissions" without a separate click, though in
             * practice every contact has at least one (the submission that created it). */
            List<Submission> submissions,
            /** This contact's Square appointment history, most recent/upcoming first. Only
             * fetched (live, from Square) when a Square customer is already known; empty
             * (never null) otherwise or if Square is unreachable — the UI doesn't need to
             * distinguish those cases from "genuinely no appointments".
             */
            List<Appointment> appointments,
            Instant createdAt
    ) {}

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

    /** Rendered when the marketing schema/table isn't reachable yet. */
    public static MarketingContactDto unavailable() {
        return new MarketingContactDto(false, List.of());
    }
}
