package com.salonreview.web.dto;

import java.math.BigDecimal;
import java.util.List;

/** All-time customer lifetime value, broken down by acquisition channel, for one landing page —
 * pairs with the Ads Report's per-period cost figures to answer "which channel's customers are
 * actually worth it long-term", not just "which channel books the cheapest first visit". Unlike
 * {@link MarketingAdsReportDto}, this is never period-bucketed: a customer's LTV is their total
 * revenue collected across every visit since their first touch, regardless of when that revenue
 * landed.
 */
public record MarketingLtvDto(
        /** One row per recognized channel (meta_ads, google_ads, instagram_organic, google_organic,
         * direct), always present even at zero customers so a channel with no acquisitions yet is
         * visible as a zero row rather than silently missing — plus "other" only when at least one
         * customer's channel didn't classify into any of the five. */
        List<ChannelLtv> channels,
        /** Every channel combined — the same distinct-customer/no-double-counting guarantee as the
         * Ads Report's own totals row. */
        ChannelLtv totals
) {
    public record ChannelLtv(
            String channel,
            /** Distinct customers attributed to this channel who completed at least one real, paid
             * visit — a lead who was only ever captured (never actually paid — e.g. a cancelled-only
             * booking, or a contact who never converted) doesn't count here at all, in either the
             * numerator or denominator. This is a deliberate correction from an earlier version that
             * counted every attributed lead (at $0 for non-payers) — that inflated the customer count
             * with people who never became real customers and understated true average LTV. */
            long customerCount,
            /** All-time gross revenue collected (any visit, any date) from exactly these customers. */
            BigDecimal totalRevenue,
            /** totalRevenue / customerCount — null when customerCount is 0 (nothing to divide by). */
            BigDecimal averageLtv
    ) {}
}
