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
     */
    public record UpcomingAppointment(
            String customerId,
            String customerName,
            String serviceName,
            Instant startAt,
            BigDecimal price,
            boolean freshFromAds
    ) {}
}
