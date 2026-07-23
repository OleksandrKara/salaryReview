package com.salonreview.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Gross revenue attributed to customers whose first- or latest-touch traffic source was a paid
 * ad click (Meta and/or Google, per the requested {@code sources}), for services rendered within
 * [from, to] inclusive — split into three segments: every ads customer, only those whose Square
 * record was created fresh off that ad touch (a genuinely new customer the ad brought in), and only
 * those who already existed in Square before coming back through one (still a real ad-driven visit,
 * just not new business). Also carries every still-upcoming appointment for an ads-attributed
 * customer, the current calendar month's ad spend/ROI inputs (independent of [from, to] — "so far
 * this month" is always the current month, whatever range is being viewed above it), and the ads
 * spend figure itself.
 */
public record MarketingAnalyticsDto(
        LocalDate from,
        LocalDate to,
        Segment all,
        Segment fresh,
        Segment returning,
        List<UpcomingAppointment> upcoming,
        /** Every ads-attributed appointment within [from, to] that actually collected money —
         * one row per booking, sourced from the same matched payroll lines the segments above
         * are summed from, so it's a real collected amount (not a catalog estimate) with the
         * real payment channel. Owner/family comps are excluded (nothing was collected).
         */
        List<CompletedAppointment> completed,
        /** Every real Square booking for an ads-attributed customer that didn't happen (cancelled
         * by either side, declined, or no-show) — one row per booking, any date (past or future
         * relative to today; a booking can be cancelled ahead of its own date). Not restricted to
         * [from, to]: same "the drill-down should be able to reconcile every headline number, not
         * just the ones a narrower fetch happened to include" reasoning as broadening {@code
         * upcoming} above — the Ads Report's own "Cancelled" count sums this same, unrestricted
         * set filtered to [from, to] by date; see MarketingAnalyticsService#buildCancelledAppointments.
         */
        List<CancelledAppointment> cancelled,
        /** Gross revenue for every ads customer, fixed to [1st of the current month, today] —
         * independent of the requested [from, to] — so the ROI card always means "this month",
         * matching the ad-spend figure below.
         */
        Segment currentMonthToDate,
        /** Manually-entered ad spend for the current calendar month; zero if never entered. */
        BigDecimal adSpendThisMonth
) {
    /** Distinct-customer / service-line-item / gross-revenue rollup for one customer segment.
     * serviceCount counts individual service line items (e.g. a mani+pedi visit counts as 2),
     * matching how SquareMonthAggregator.AttributedService already represents "a service"
     * everywhere else in this app. grossRevenue is the sum of AttributedService.gross() — menu-price
     * revenue, the same "gross" already used throughout the owner overview/revenue-pulse reports.
     */
    public record Segment(long customerCount, long serviceCount, BigDecimal grossRevenue) {}

    /** One still-future appointment for an ads-attributed customer — one row per booking (not per
     * service segment, unlike Segment.serviceCount), since "an upcoming visit" is the natural unit
     * for a forward-looking list. price is the summed catalog list price of its service segment(s).
     * capturedInRange is whether this customer's own firstTouch fell within the requested [from,
     * to] — the same cohort restriction the Ads Report's "Anticipated (outside period)" figure
     * uses, exposed here so the frontend can split this single list into "this period" (startAt
     * within [from, to], any customer) vs. "outside period" (startAt outside it, but only for
     * capturedInRange customers) and have both sums agree with those headline figures exactly.
     */
    public record UpcomingAppointment(
            String customerId,
            String customerName,
            String serviceName,
            Instant startAt,
            BigDecimal price,
            boolean freshFromAds,
            boolean capturedInRange
    ) {}

    /** One real Square booking for an ads-attributed customer that didn't happen — cancelled by
     * either side, declined, or a no-show. price is a catalog estimate (there's nothing actually
     * collected to report, unlike CompletedAppointment). status is Square's own raw booking status
     * ("CANCELLED_BY_CUSTOMER", "CANCELLED_BY_SELLER", "DECLINED", or "NO_SHOW"). capturedInRange
     * mirrors UpcomingAppointment's field of the same name, same reasoning.
     */
    public record CancelledAppointment(
            String customerId,
            String customerName,
            String serviceName,
            LocalDate date,
            BigDecimal price,
            String status,
            boolean freshFromAds,
            boolean capturedInRange
    ) {}

    /** One already-completed, actually-paid appointment for an ads-attributed customer — one row
     * per booking (a multi-service visit's lines are summed into one), most recent first.
     * paymentChannel is "CASH" (checked out as cash in Square), "CARD", or "CASH-NOTE" (a
     * provider's note, no Square checkout) — the same classification used for payroll.
     */
    public record CompletedAppointment(
            String customerId,
            String customerName,
            String serviceName,
            LocalDate date,
            BigDecimal collected,
            String paymentChannel,
            boolean freshFromAds
    ) {}
}
