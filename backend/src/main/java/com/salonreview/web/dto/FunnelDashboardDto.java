package com.salonreview.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One landing-page variant's booking funnel. Keyed by variant, not by {@code flowKey} — more than
 * one variant can share the same flow shape (e.g. two differently-designed A/B variants both
 * testing "contact info last"), and the owner needs each variant's own numbers, not a pooled total
 * across every variant that happens to use the same flow. {@code flowKey} is kept as descriptive
 * metadata about which funnel shape this variant uses, not the grouping key. An empty list from
 * {@link com.salonreview.marketing.FunnelAnalyticsService} means "nothing to show" (schema
 * unreachable, unknown slug, or simply no funnel events recorded yet).
 */
public record FunnelDashboardDto(
        String landingPageSlug,
        UUID variantId,
        /** marketing.landing_variants.name — the same label shown in manage_landing_variants.py's
         * own `list` output and the main marketing dashboard's variant breakdown. */
        String variantName,
        /** marketing.landing_variants.key — the ?v= deep-link key, or null if this variant has
         * none (random-pool-only, no direct campaign link). */
        String variantKey,
        /** marketing.landing_variants.weight at the time of this read — 0 means excluded from the
         * random A/B pool (still reachable via variantKey's deep link, if it has one). */
        int variantWeight,
        /** marketing.landing_variants.active — false means the variant itself has been
         * deactivated outright (distinct from weight=0, which just excludes it from the random
         * pool while keeping it reachable via its deep link). */
        boolean variantEnabled,
        /** Which booking-flow shape this variant uses (see lib/funnelFlow.ts's BOOKING_FLOWS) —
         * descriptive only; two variants can share the same flowKey and still get separate rows
         * here. */
        String flowKey,
        /** This variant's own page_view count for the selected period/sources. */
        long totalVisitors,
        /** Distinct sessions reaching this variant's flow's first step (step_index = 0). */
        long totalStarted,
        List<FunnelStepStat> steps,
        /** Sourced from marketing.attribution (Square-reconciled), same as the main dashboard's
         * "Bookings" column — filtered to this variant_id, not shared/pooled across variants. */
        long totalCompleted,
        /** totalCompleted / totalVisitors, 0 when totalVisitors is 0. */
        double finalConversionRate,
        /** True when this variant has recorded activity within the last {@code ACTIVE_WINDOW}
         * (see FunnelAnalyticsService) — i.e. it's still actually receiving traffic. False means
         * this variant's weight has been zeroed or it's been deactivated a while ago — the data
         * stays (nothing is deleted), it's just no longer part of the live experiment, so the UI
         * can show it as history rather than as if it were still an active, ongoing test. */
        boolean active,
        /** When this variant last recorded any funnel event at all. */
        Instant lastActivityAt
) {
    public record FunnelStepStat(
            String stepKey,
            int stepIndex,
            int stepCountTotal,
            long reachedCount,
            /** reachedCount / totalStarted, 0 when totalStarted is 0. */
            double reachedPctOfStarted,
            /** How many sessions reached the previous step (or totalStarted, for the first
             * step) but not this one. */
            long dropOffCount,
            /** dropOffCount / previous step's reachedCount (or totalStarted for the first
             * step), 0 when the denominator is 0. */
            double dropOffPct
    ) {}

}
