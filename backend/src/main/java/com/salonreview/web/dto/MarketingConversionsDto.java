package com.salonreview.web.dto;

import java.math.BigDecimal;
import java.util.List;

/** Weighted conversion summary for one landing page and period: how many people did each
 * conversion, what each is worth in points, and the real Square revenue those people paid. */
public record MarketingConversionsDto(
        boolean available,
        String landingPageSlug,
        long pageViews,
        List<Row> rows,
        long totalPoints,
        long payingCustomers,
        BigDecimal revenue
) {
    public record Row(String key, String label, String hint, int weight, long count, long points) {}

    public static MarketingConversionsDto unavailable(String slug) {
        return new MarketingConversionsDto(false, slug, 0, List.of(), 0, 0, BigDecimal.ZERO);
    }
}
