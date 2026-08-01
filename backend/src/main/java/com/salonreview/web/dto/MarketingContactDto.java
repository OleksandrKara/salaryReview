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
            /** Last name — resolved from Square (marketing.contacts itself has no family_name
             * column; the booking form collects one, but it's never persisted there), best-effort
             * only when a Square customer is already linked. Null otherwise — never worth a live
             * phone-lookup just for display here (see MarketingContactsService#toContact). */
            String familyName,
            String phoneNumber,
            String emailAddress,
            String originalTrafficSource,
            String marketingTrafficSource,
            /** One of the five TrafficSourceSql buckets (meta_ads, google_ads, instagram_organic,
             * google_organic, direct), computed server-side from this contact's own utm/referrer
             * columns — null for the rare edge case that fits none of them. Prefer this over
             * originalTrafficSource/marketingTrafficSource for filtering: those are salonLandings'
             * own classify_traffic_source() labels, which (as of this field's addition) can
             * mislabel an organic Instagram bio-link/post click as "Meta Ads" — see TrafficSourceSql. */
            String channel,
            /** Latest touch's raw UTM — like marketingTrafficSource, overwritten on every
             * capture event, not preserved as first-touch. */
            String utmSource,
            String utmMedium,
            String utmCampaign,
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
            Instant createdAt,
            Instant updatedAt,
            /** Most recent time this contact was sent a checkout-review Google-review link, or
             * null if never — see {@code SmsMessageLogService.LinkEngagement}. Null, not a
             * missing/empty state: this contact simply never went through that automation. */
            Instant googleReviewSentAt,
            /** Most recent time this contact actually clicked through to Google, or null if never
             * (including "sent but hasn't clicked yet", same as googleReviewSentAt non-null with
             * this null). Used by the checkout-review automation itself (see
             * CheckoutReviewReplyService) and shown in the contact sidebar so a manager can see at
             * a glance whether this person is a proven repeat reviewer. */
            Instant googleReviewClickedAt,
            /** Same pair, for the private feedback-form link (negative branch, or a repeat
             * reviewer's positive branch — see CheckoutReviewLinks). */
            Instant feedbackFormSentAt,
            Instant feedbackFormClickedAt,
            /** True once this Square customer's distinct-day visit count (see provider_visit)
             * reaches the configured vip.visit-threshold — a repeat client worth the owner's
             * special attention. Strictly data-driven, no manual override (see
             * MarketingContactsService#visitCountsByCustomerId). Always false when no Square
             * customer is known yet. */
            boolean vip,
            /** The distinct-day visit count backing {@code vip}, or null when no Square customer
             * is known (there's nothing to count) — distinguishes "0 real visits on record" from
             * "not applicable" in the UI. */
            Integer visitCount
    ) {}

    public record Submission(
            String submissionType,
            Instant occurredAt,
            String landingPageSlug,
            String variantName,
            /** Same classify_traffic_source() label used for a contact's own traffic-source
             * fields (e.g. "Direct / No referrer", "google / cpc / promo") — never blank for a
             * submission recorded after this column existed; null only on older rows. */
            String trafficSource,
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
            String artistName,
            /** How this appointment was actually paid — "CASH" (checked out as cash in Square),
             * "CARD", or "CASH-NOTE" (a provider's cash note, no Square checkout) — the same
             * classification SquareMonthAggregator uses for payroll. Null if this appointment
             * isn't tied to a matched payment: still upcoming, cancelled/no-show/declined, or a
             * past visit with no matching order or cash note found. */
            String paymentChannel,
            /** What was actually collected for this appointment (after any discount), from the
             * same matched order/cash-note SquareMonthAggregator uses for payroll — unlike price
             * above, this is the real amount, not a catalog estimate. Null under the same
             * conditions as paymentChannel. */
            BigDecimal collectedAmount,
            /** The rest of these come from the marketing.submissions row that actually created
             * this booking (matched by square_booking_id) — all null if this appointment didn't
             * originate through our own booking funnel (e.g. booked in person, or through Square
             * directly, before we ever tracked this customer).
             */
            String trafficSource,
            String deviceType,
            String osName,
            String osVersion,
            String browserName,
            Instant submissionOccurredAt
    ) {}

    /** Rendered when the marketing schema/table isn't reachable yet. */
    public static MarketingContactDto unavailable() {
        return new MarketingContactDto(false, List.of());
    }
}
