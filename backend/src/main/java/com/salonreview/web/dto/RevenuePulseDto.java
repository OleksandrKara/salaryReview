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
        /** Card portion of {@link #currentGross} (orders paid mostly by card). */
        BigDecimal currentCard,
        /** Cash portion of {@link #currentGross} (orders paid mostly in cash). */
        BigDecimal currentCash,
        /** Tips earned day 1 → currentEndDay — separate from {@link #currentGross} (not a tender, not
         * part of the card/cash total), same as tips are tracked apart from gross revenue elsewhere. */
        BigDecimal currentTip,
        /** Gross revenue day 1 → priorEndDay of last month. */
        BigDecimal priorGross,
        /** Card portion of {@link #priorGross}. */
        BigDecimal priorCard,
        /** Cash portion of {@link #priorGross}. */
        BigDecimal priorCash,
        /** Tips earned day 1 → priorEndDay of last month. */
        BigDecimal priorTip,
        /** (currentGross − priorGross) / priorGross × 100; null when priorGross = 0. */
        BigDecimal deltaPct,
        /** Non-cancelled future appointments remaining this month. */
        int upcomingBookings,
        /** Sum of catalog prices for those appointments. */
        BigDecimal upcomingGross,
        /** currentGross + upcomingGross — the transparent "naive" ceiling. Kept as a cross-check. */
        BigDecimal projectedMonthGross,
        /** Forecaster's best estimate, blending pattern match + booking-ceiling calibration. */
        BigDecimal projectedMid,
        /** Projected month-end card revenue — {@link #projectedMid} split by the recent card share. */
        BigDecimal projectedCard,
        /** Projected month-end cash revenue — {@link #projectedMid} split by the recent cash share. */
        BigDecimal projectedCash,
        /** Projected month-end tips — {@link #projectedMid} × the recent tip-to-gross ratio; zero when
         * there's no realized revenue yet to infer a ratio from. */
        BigDecimal projectedTip,
        /** Confidence range floor — null in cold-start mode (insufficient history). */
        BigDecimal projectedLow,
        /** Confidence range ceiling — null in cold-start mode. */
        BigDecimal projectedHigh,
        /** Distinct months of usable calibration data behind the forecast (0 = pattern-only). */
        int forecastCalibrationDataPoints,
        /** Settled months of PeriodEntry history behind the forecast (3+ enables pattern match). */
        int forecastHistoryMonths,
        /** Total days in the current month — lets the UI flag when the two months differ in length. */
        int currentMonthLength,
        /** Total days in the prior month — e.g. 31 for May even when only 30 are compared to June. */
        int priorMonthLength,
        /**
         * What the app was actually projecting on the same day last month — pulled from that day's
         * {@code revenue_snapshot} row (real MTD revenue + real booked-ahead pipeline as they stood
         * then, not a recomputation). Null for past-month views (only meaningful relative to "now"),
         * or when no snapshot exists for that date (e.g. before data collection started).
         */
        BigDecimal priorProjected,
        /** (projectedMid − priorProjected) / priorProjected × 100; null when priorProjected is null/zero. */
        BigDecimal projectedDeltaPct
) {}
