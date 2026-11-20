package com.salonreview.web.dto;

import java.math.BigDecimal;
import java.util.List;

/** Conversions on one landing page and period, split by traffic channel. */
public record MarketingConversionsDto(
        boolean available,
        String landingPageSlug,
        long pageViews,
        List<SourceRow> bySource
) {
    /** One traffic channel: key is meta_ads, google_ads, instagram_organic, google_organic, yelp, direct or other. */
    public record SourceRow(String key, long visitors, long smsTaps, long callTaps, long consultations,
                            long deposits, long fullPayments, long payingCustomers, BigDecimal revenue) {}

    public static MarketingConversionsDto unavailable(String slug) {
        return new MarketingConversionsDto(false, slug, 0, List.of());
    }
}
