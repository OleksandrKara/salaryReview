package com.salonreview.web.dto;

import java.math.BigDecimal;
import java.util.List;

/** Weighted conversion summary for one landing page and period: how many people did each
 * conversion, what each is worth in points, and the real Square revenue those people paid.
 * rows/totals cover the selected traffic sources; bySource always lists every channel. */
public record MarketingConversionsDto(
        boolean available,
        String landingPageSlug,
        long pageViews,
        List<Row> rows,
        long totalPoints,
        long payingCustomers,
        BigDecimal revenue,
        List<SourceRow> bySource,
        GoogleAds googleAds
) {
    public record Row(String key, String label, String hint, int weight, long count, long points) {}

    /** One traffic channel: key is meta_ads, google_ads, instagram_organic, google_organic, yelp, direct or other. */
    public record SourceRow(String key, long visitors, long smsTaps, long callTaps, long consultations,
                            long deposits, long fullPayments, long payingCustomers, BigDecimal revenue, long points) {}

    public record Campaign(long campaignId, String name, String status, BigDecimal spend, long impressions,
                           long clicks, double platformConversions) {}

    /** Spend from the daily Google Ads sync for the same period; null when nothing was synced. */
    public record GoogleAds(BigDecimal spend, long impressions, long clicks, double platformConversions,
                            List<Campaign> campaigns) {}

    public static MarketingConversionsDto unavailable(String slug) {
        return new MarketingConversionsDto(false, slug, 0, List.of(), 0, 0, BigDecimal.ZERO, List.of(), null);
    }
}
