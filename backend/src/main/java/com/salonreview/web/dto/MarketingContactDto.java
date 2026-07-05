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
            /** True once this lead has completed a real Square booking. */
            boolean hasAppointment,
            /** Square Dashboard customer profile link, or null if no Square customer exists yet. */
            String squareProfileUrl,
            String bookingStatus,
            Instant bookingStartAt,
            String bookingServiceName,
            BigDecimal bookingPrice,
            String bookingArtistName,
            Instant createdAt
    ) {}

    /** Rendered when the marketing schema/table isn't reachable yet. */
    public static MarketingContactDto unavailable() {
        return new MarketingContactDto(false, List.of());
    }
}
