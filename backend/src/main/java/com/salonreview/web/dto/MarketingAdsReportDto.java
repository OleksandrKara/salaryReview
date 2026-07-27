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
        /** "WEEK", "MONTH", "MONTH_TO_DATE", or "CUSTOM" — which grain {@code periods} is
         * bucketed into. WEEK/MONTH may return several historical rows (a trend); MONTH_TO_DATE
         * and CUSTOM always return exactly one. */
        String periodType,
        /** One row per period, most recent first. */
        List<PeriodRow> periods,
        /** Sum (or, for adSpendEstimated, OR) across every row in {@code periods} — the report's
         * grand-total row, so the marketing team doesn't have to add up the table by hand. */
        PeriodRow totals
) {
    /** A money figure broken into what came from a customer's very first ads-attributed visit
     * (firstVisit) vs. every later visit by that same customer (repeat) — {@code firstVisit +
     * repeat} always equals the un-split figure it accompanies. "First visit" mirrors the same
     * freshness check {@link com.salonreview.web.dto.MarketingAnalyticsDto.CompletedAppointment
     * #freshFromAds} already uses elsewhere, not a separate definition. */
    public record MoneySplit(BigDecimal firstVisit, BigDecimal repeat) {}

    /** Same split as {@link MoneySplit}, for a headline count instead of a dollar figure —
     * {@code firstVisit + repeat} always equals the un-split count it accompanies. */
    public record CountSplit(long firstVisit, long repeat) {}

    public record PeriodRow(
            LocalDate periodStart,
            LocalDate periodEnd,
            /** Ad spend attributed to this period, resolved from the flexible per-page
             * {@code ad_spend_entries} ledger (see AdSpendResolver) — prorated by calendar-day
             * overlap when entries don't exactly tile the period. */
            BigDecimal adSpend,
            /** True when adSpend needed any proration (a gap, an overlap, or a clipped entry) —
             * false only when entries exactly, non-overlappingly tile the period. */
            boolean adSpendEstimated,
            /** What was actually collected (cash/card/cash-note) for ads-attributed appointments
             * completed in this period — the same real, matched-payroll figure the Analytics tab's
             * "Completed appointments" list is built from, not a catalog estimate — plus any
             * manager-follow-up appointment (see MarketingContactsService#followUpAppointments)
             * whose payment was matched the same way. */
            BigDecimal revenueCollected,
            /** {@link #revenueCollected} split by whether it came from a customer's first
             * ads-attributed visit or a later, repeat one. */
            MoneySplit revenueCollectedSplit,
            /** Catalog-price value of still-upcoming ads-attributed appointments scheduled within
             * this period — zero for periods entirely in the past — including manager-follow-up
             * appointments not yet paid. */
            BigDecimal anticipatedRevenue,
            /** {@link #anticipatedRevenue} split by first-visit vs. repeat. */
            MoneySplit anticipatedRevenueSplit,
            /** Ads-attributed customers whose Square record was created fresh off the ad touch
             * (see MarketingAnalyticsService#isFresh), with a service rendered in this period —
             * booked through the tracked flow itself, not a manager follow-up (see
             * {@link #customersFollowedUp}). */
            long customersCreated,
            /** Catalog-price value of every still-upcoming appointment dated outside this row's own
             * [periodStart, periodEnd], booked by exactly the ads-attributed customers whose own
             * firstTouch falls within that same window — "of the leads this specific period brought
             * in, what have they got booked beyond it". Deliberately scoped to this window's own new
             * customers rather than every ads-attributed customer ever: the latter would make this
             * figure identical for every past period, since a past period's own dates can never
             * contain a future appointment regardless of whose it is. {@code revenueCollected +
             * anticipatedRevenue + anticipatedRevenueOutsidePeriod} is what the WhatsApp text export
             * calls "Total". */
            BigDecimal anticipatedRevenueOutsidePeriod,
            /** {@link #anticipatedRevenueOutsidePeriod} split by first-visit vs. repeat. */
            MoneySplit anticipatedRevenueOutsidePeriodSplit,
            /** Count of distinct completed, actually-paid appointments (bookings, not service line
             * items) in this period — comps excluded, same as revenueCollected. */
            long completedAppointments,
            /** {@link #completedAppointments} split by first-visit vs. repeat. */
            CountSplit completedAppointmentsSplit,
            /** Real Square bookings for ads-attributed customers whose own start date falls within
             * this period but that didn't happen (cancelled by customer/seller, declined, or
             * no-show) — the piece of "what happened to bookings in this period" that wasn't
             * tracked anywhere before. completedAppointments + cancelledBookings +
             * anticipatedAppointments is the full bookings breakdown for this period. */
            long cancelledBookings,
            /** Count of still-upcoming appointments scheduled within this period — the same
             * appointments anticipatedRevenue above sums the price of, just the headline count.
             * completedAppointments + cancelledBookings + anticipatedAppointments +
             * anticipatedAppointmentsOutsidePeriod is the full bookings breakdown for this period. */
            long anticipatedAppointments,
            /** {@link #anticipatedAppointments} split by first-visit vs. repeat. */
            CountSplit anticipatedAppointmentsSplit,
            /** Count of still-upcoming appointments dated outside this row's own period, booked by
             * exactly the customers captured (firstTouch) within that same window — the headline
             * count for {@link #anticipatedRevenueOutsidePeriod}, same scoping and same reasoning. */
            long anticipatedAppointmentsOutsidePeriod,
            /** {@link #anticipatedAppointmentsOutsidePeriod} split by first-visit vs. repeat. */
            CountSplit anticipatedAppointmentsOutsidePeriodSplit,
            /** Real, non-cancelled Square appointments in this period for this page's
             * ads-attributed contacts that the tracked flow never recorded — a lead a manager
             * booked by phone after the on-site flow didn't complete. Already folded into
             * revenueCollected/anticipatedRevenue above; this is just the headline count. */
            long customersFollowedUp,
            /** True when this row's periodEnd is still in the future relative to today — a Full
             * Month report viewed before the month closes. Always false for WEEK/MONTH_TO_DATE/
             * CUSTOM rows that don't extend past today. */
            boolean monthInProgress,
            /** Distinct customers behind {@link #completedAppointments} — a customer with two
             * completed visits in the same period counts once here but twice there. Answers "how
             * many people" alongside "how many bookings", the same distinction the Ads Report's new
             * Customers block draws for every bucket below. */
            long customersCollected,
            /** Distinct customers behind {@link #cancelledBookings}. */
            long customersCancelled,
            /** Distinct customers behind {@link #anticipatedAppointments}. */
            long customersAnticipated,
            /** Distinct customers behind {@link #anticipatedAppointmentsOutsidePeriod} — same
             * captured-in-this-window scoping, not summed across rows for the same reason (see
             * {@link #anticipatedAppointmentsOutsidePeriod}'s own doc). */
            long customersAnticipatedOutsidePeriod
    ) {}
}
