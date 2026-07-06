package com.salonreview.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Gross revenue attributed to customers whose first- or latest-touch traffic source was a paid
 * ad click (Meta or Google), for services rendered within [from, to] inclusive.
 */
public record MarketingAnalyticsDto(
        LocalDate from,
        LocalDate to,
        /** Distinct ads-attributed customers who had at least one service in range. */
        long customerCount,
        /** Individual service line items (e.g. a mani+pedi visit counts as 2), matching how
         * SquareMonthAggregator.AttributedService already represents "a service" everywhere else
         * in this app.
         */
        long serviceCount,
        /** Sum of AttributedService.gross() — menu-price revenue, the same "gross" already used
         * throughout the owner overview/revenue-pulse reports.
         */
        BigDecimal grossRevenue
) {}
