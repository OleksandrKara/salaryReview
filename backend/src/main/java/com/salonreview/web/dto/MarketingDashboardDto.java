package com.salonreview.web.dto;

import java.util.List;

public record MarketingDashboardDto(
        boolean available,
        String landingPageSlug,
        String experimentStatus,
        List<VariantStat> variants,
        /** ISO-8601 instant, or null if no cutoff is set — stats below reflect activity from
         * this point forward only (see MarketingDashboardService.updateStatsSince). */
        String statsSince
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
            String deepLinkUrl,
            /** What this variant is testing and why, e.g. "urgency-focused headline + green
             * accent vs. control's neutral tone" — free text, null if never set. */
            String description
    ) {}

    /** Rendered when the marketing schema/tables aren't reachable yet, or the slug is unknown. */
    public static MarketingDashboardDto unavailable(String slug) {
        return new MarketingDashboardDto(false, slug, "none", List.of(), null);
    }
}
