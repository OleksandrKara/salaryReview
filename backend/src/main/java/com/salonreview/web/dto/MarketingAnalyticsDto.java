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
            /** When this reservation was actually made (the Square booking's own {@code
             * created_at}) — the field period-bucketing (Ads Report's week/month rows) keys on,
             * so a customer who books late in one period for a visit that lands in the next
             * period is still counted in the period they actually booked in, not the one their
             * visit happens to fall in. {@code startAt} above is kept purely for display ("when
             * is this appointment"). Falls back to {@code startAt} when the booking's creation
             * date couldn't be resolved (e.g. a manager follow-up appointment, or Square's own
             * created_at being unavailable) — the same period {@code startAt} would suggest,
             * rather than dropping the row from every period's bucket entirely.
             */
            Instant bookedAt,
            BigDecimal price,
            boolean freshFromAds,
            boolean capturedInRange,
            /** The real Square booking id — lets the frontend key each row uniquely instead of by
             * (customerId, startAt), which two genuinely different bookings could share if Square's
             * own start_at ever collided (defensive; startAt alone hasn't been observed to collide
             * in practice, unlike CompletedAppointment's date+serviceName below). Nullable only for
             * a follow-up appointment whose underlying Appointment DTO didn't carry one.
             */
            String bookingId
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
            /** See {@link UpcomingAppointment#bookedAt}'s doc — same reasoning, this is what
             * period-bucketing keys on instead of {@code date} (the cancelled visit's own date). */
            LocalDate bookedDate,
            BigDecimal price,
            String status,
            boolean freshFromAds,
            boolean capturedInRange,
            /** See CompletedAppointment.bookingId's doc — same reasoning: two cancelled bookings for
             * the same customer, same day, same service name are a real (if less common) case this
             * disambiguates for the frontend's row key. */
            String bookingId
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
            /** See {@link UpcomingAppointment#bookedAt}'s doc — same reasoning, this is what
             * period-bucketing keys on instead of {@code date} (the visit's own date). */
            LocalDate bookedDate,
            BigDecimal collected,
            String paymentChannel,
            boolean freshFromAds,
            /** The real Square booking id. Two genuinely different appointments for the same
             * customer on the same calendar day can carry the identical generic serviceName
             * "cash note (N counted)" (SquareMonthAggregator's label for any cash-note booking,
             * regardless of the actual service) — (customerId, date, serviceName) alone is not a
             * unique row identity in that case. Seen in production: Ashanti Williamson's two same-day
             * cash appointments collided on the frontend's derived row key, causing a React duplicate-
             * key rendering bug (a ghost row appearing after switching ledger tabs, with both rows'
             * expand state incorrectly shared). bookingId is always non-null here — {@code
             * buildCompletedAppointments} groups by it, {@code mergeFollowUpsInto} carries it through
             * from the underlying Appointment DTO (nullable there only in principle).
             */
            String bookingId
    ) {}
}
