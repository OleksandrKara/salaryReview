package com.salonreview.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Ad spend, ROI inputs, and volume metrics broken out per week or per month, for the Ads Report
 * tab — the marketing team's at-a-glance view of how each period actually performed, side by side.
 * Unlike {@link MarketingAnalyticsDto#analytics}, which returns one aggregated total for a whole
 * [from, to] range, this buckets the same underlying data into a row per period, most recent first.
 */
public record MarketingAdsReportDto(
        /** "WEEK" or "MONTH" — which grain {@code periods} is bucketed into. */
        String periodType,
        /** One row per period, most recent first. */
        List<PeriodRow> periods,
        /** Sum (or, for adSpendEstimated, OR) across every row in {@code periods} — the report's
         * grand-total row, so the marketing team doesn't have to add up the table by hand. */
        PeriodRow totals
) {
    public record PeriodRow(
            LocalDate periodStart,
            LocalDate periodEnd,
            /** Ad spend attributed to this period. Real, manually-entered figures for MONTH rows;
             * for WEEK rows, always {@link #adSpendEstimated} — the containing month's real figure
             * prorated by calendar day, since spend is only ever entered once per month. */
            BigDecimal adSpend,
            /** True when adSpend is a prorated estimate rather than a real entered figure — always
             * true for WEEK rows, always false for MONTH rows. */
            boolean adSpendEstimated,
            /** What was actually collected (cash/card/cash-note) for ads-attributed appointments
             * completed in this period — the same real, matched-payroll figure the Analytics tab's
             * "Completed appointments" list is built from, not a catalog estimate. */
            BigDecimal revenueCollected,
            /** Catalog-price value of still-upcoming ads-attributed appointments scheduled within
             * this period — zero for periods entirely in the past. */
            BigDecimal anticipatedRevenue,
            /** Ads-attributed customers whose Square record was created fresh off the ad touch
             * (see MarketingAnalyticsService#isFresh), with a service rendered in this period. */
            long customersCreated,
            /** Count of distinct completed, actually-paid appointments (bookings, not service line
             * items) in this period — comps excluded, same as revenueCollected. */
            long completedAppointments
    ) {}
}
