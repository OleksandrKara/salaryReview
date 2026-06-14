package com.salonreview.web.dto;

import java.math.BigDecimal;

public record RevenuePulseDto(
        int year,
        int month,
        /** How many days of the current period are covered (1–today for current month; full length for past). */
        int currentDays,
        /** Last day of the current period included (day-of-month, e.g. 6). */
        int currentEndDay,
        /** Last day of the prior period included (same ordinal, clamped to prior month length). */
        int priorEndDay,
        /**
         * Wall-clock time-of-day used as the cutoff for both current and prior windows, formatted
         * "h:mm a" in the salon timezone (e.g. "11:22 PM"). Non-null only for the current calendar
         * month — past months compare full calendar days, so no time cutoff applies.
         */
        String asOfTime,
        /** Gross revenue day 1 → currentEndDay of this month. */
        BigDecimal currentGross,
        /** Gross revenue day 1 → priorEndDay of last month. */
        BigDecimal priorGross,
        /** (currentGross − priorGross) / priorGross × 100; null when priorGross = 0. */
        BigDecimal deltaPct,
        /** Non-cancelled future appointments remaining this month. */
        int upcomingBookings,
        /** Sum of catalog prices for those appointments. */
        BigDecimal upcomingGross,
        /** currentGross + upcomingGross — rough month projection. */
        BigDecimal projectedMonthGross
) {}
