package com.salonreview.timecards;

import java.time.ZoneId;

/** California thresholds in one place. See openspec/changes/w2-time-tracking/compliance.md. */
public record CaWageRules(
        ZoneId zone,
        int padMinutes,
        int noShowCreditMinutes,
        int mealMinutes,
        int mealRequiredAfterMinutes,
        int mealWaiverMaxMinutes,
        int secondMealRequiredAfterMinutes,
        int secondMealWaiverMaxMinutes,
        int restMinutes,
        int restFirstAfterMinutes,
        int dailyOvertimeMinutes,
        int doubleTimeMinutes,
        int weeklyOvertimeMinutes,
        int minimumWageCents,
        int splitShiftGapMinutes,
        int reportingMinimumMinutes) {

    public static CaWageRules defaults() {
        return new CaWageRules(
                ZoneId.of("America/Los_Angeles"),
                15, 15,
                30, 300, 360, 600, 720,
                10, 210,
                480, 720, 2400,
                1775,
                60,
                120);
    }

    /** Paid 10 minute rests owed for a day with this many paid minutes (over 3.5h: 1, over 6h: 2, over 10h: 3, ...). */
    public int restsRequired(long paidMinutes) {
        if (paidMinutes <= restFirstAfterMinutes) return 0;
        return (int) Math.ceil((paidMinutes - 120) / 240.0);
    }
}
