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
            /** Distinct customers ever attributed to this channel for this page — the LTV
             * denominator, not just customers who happened to pay something (a customer who
             * cancelled their only booking still counts here, at $0 lifetime value). */
            long customerCount,
            /** All-time gross revenue collected (any visit, any date) from exactly these customers. */
            BigDecimal totalRevenue,
            /** totalRevenue / customerCount — null when customerCount is 0 (nothing to divide by). */
            BigDecimal averageLtv
    ) {}
}
