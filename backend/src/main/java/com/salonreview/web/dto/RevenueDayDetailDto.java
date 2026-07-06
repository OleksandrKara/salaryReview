package com.salonreview.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * What was known and projected as of a specific past (or today's) date, from that day's frozen
 * {@code revenue_snapshot} row plus a forecast recomputed from its stored inputs.
 *
 * @param hasSnapshot    false when no snapshot was ever captured for this date — every other field
 *                       is null/zero in that case
 * @param mtdRevenue     month-to-date revenue as of this date (card + cash)
 * @param projectedMid   month-end forecast recomputed from this date's own MTD/upcoming inputs
 * @param projectedLow   null in cold-start mode (insufficient history for a range)
 * @param projectedHigh  null in cold-start mode
 * @param monthEndActual the month's actual final total, once settled; null while still open
 */
public record RevenueDayDetailDto(
        LocalDate date,
        boolean hasSnapshot,
        BigDecimal mtdRevenue,
        BigDecimal mtdCard,
        BigDecimal mtdCash,
        int mtdServices,
        int upcomingCount,
        BigDecimal upcomingGross,
        BigDecimal projectedMid,
        BigDecimal projectedLow,
        BigDecimal projectedHigh,
        BigDecimal monthEndActual
) {
    public static RevenueDayDetailDto noSnapshot(LocalDate date) {
        return new RevenueDayDetailDto(date, false, null, null, null, 0, 0, null, null, null, null, null);
    }
}
