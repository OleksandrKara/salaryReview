package com.salonreview.web.dto;

import java.util.List;

/**
 * One booking flow's funnel for one landing page. A landing page normally has exactly one
 * {@code flowKey} in its history; {@link com.salonreview.marketing.FunnelAnalyticsService}
 * returns a list so a flow redesign (a new flowKey) shows as its own funnel rather than being
 * silently merged with the old one's step vocabulary. An empty list means "nothing to show"
 * (schema unreachable, unknown slug, or simply no funnel events recorded yet) — there's no
 * separate "available" flag the way {@link MarketingDashboardDto} has, since a list already
 * distinguishes "zero flows" from "some flows" without needing one.
 */
public record FunnelDashboardDto(
        String landingPageSlug,
        String flowKey,
        /** Existing page_view count for this landing page — same number the main marketing
         * dashboard shows, so the two never disagree on "how many people saw this page". */
        long totalVisitors,
        /** Distinct sessions reaching this flow's first step (step_index = 0). */
        long totalStarted,
        List<FunnelStepStat> steps,
        /** Sourced from marketing.attribution (Square-reconciled), same as the main dashboard's
         * "Bookings" column — not a best-effort client "done" beacon. */
        long totalCompleted,
        /** totalCompleted / totalVisitors, 0 when totalVisitors is 0. */
        double finalConversionRate
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
