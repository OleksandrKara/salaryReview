package com.salonreview.web.dto;

import java.util.List;

public record MarketingDashboardDto(
        boolean available,
        String landingPageSlug,
        String experimentStatus,
        List<VariantStat> variants
) {
    public record VariantStat(
            String variantId,
            String name,
            int weight,
            boolean active,
            long pageViews,
            long bookingsCompleted,
            double conversionRate,
            /** Direct ?v=<key> link to view this exact variant, or null if it has no key yet. */
            String deepLinkUrl
    ) {}

    /** Rendered when the marketing schema/tables aren't reachable yet, or the slug is unknown. */
    public static MarketingDashboardDto unavailable(String slug) {
        return new MarketingDashboardDto(false, slug, "none", List.of());
    }
}
