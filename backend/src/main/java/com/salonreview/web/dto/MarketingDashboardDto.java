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
            /** Contacts (leads) captured under this variant — matched by variant name, since
             * marketing.contacts.variant_name is a denormalized snapshot at capture time, not a
             * foreign key; a later rename won't reattach older contacts to the new name. */
            long contactsCreated,
            /** Clicks on anything that opens the booking form (step 1) — see
             * MarketingDashboardRepository.RawVariantStat.bookNowClicks for the query. */
            long bookNowClicks,
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
