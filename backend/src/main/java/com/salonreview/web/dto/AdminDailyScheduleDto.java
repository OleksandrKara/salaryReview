package com.salonreview.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * The owner's day-by-day schedule view for one calendar month: every manager's shifts laid out on a
 * timeline, plus computed anomaly flags so an owner can spot a mistyped clock-in/out (e.g. an AM/PM
 * mix-up) or a coverage gap without manually eyeballing every shift. Expected shape (salon policy, not
 * enforced anywhere else): every day is staffed 8am-8pm with roughly an hour of overlap between the two
 * managers so they can hand off. {@code days} is newest-first, matching how an owner actually reviews
 * for mistakes (today's entries first).
 */
public record AdminDailyScheduleDto(
        int year,
        int month,
        String timezone,
        String expectedStartLabel,   // "8:00 AM", salon-local — for the UI's reference line/legend
        String expectedEndLabel,     // "8:00 PM"
        int expectedOverlapMinutes,  // 60 — for the UI's "expected ~1h" caption
        List<Day> days
) {
    public record Day(
            String date,             // yyyy-MM-dd
            List<Shift> shifts,      // start-time ascending
            int coverageMinutes,     // minutes in [8am,8pm) with >=1 manager clocked in
            int overlapMinutes,      // minutes in [8am,8pm) with >=2 managers clocked in concurrently
            List<String> flags       // day-level anomaly codes, see ManagerTimeService
    ) {}

    public record Shift(
            Long id,
            Long userId,
            String username,
            Instant startAt,
            Instant endAt,           // null while open
            String startLabel,       // e.g. "9:00 AM", salon-local
            String endLabel,         // null while open
            int minutes,             // 0 while open
            boolean open,
            List<String> flags       // shift-level anomaly codes, see ManagerTimeService
    ) {}
}
