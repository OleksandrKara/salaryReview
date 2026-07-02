package com.salonreview.web.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * A manager's own timesheet for one (calendar) month. {@code usdPerHour} and {@code monthPay} are
 * null when the owner hasn't set the manager's rate yet (the UI then shows hours only with a "rate not
 * set" hint). {@code monthMinutes} is always populated.
 */
public record ManagerTimesheetDto(
        int year,
        int month,
        String timezone,
        BigDecimal usdPerHour,        // null = rate not set
        int monthMinutes,
        BigDecimal monthPay,          // null when rate unset
        List<TimeEntryDto> entries,   // all shifts this month, start-time ascending
        TimeEntryDto open             // the currently-open shift, or null
) {}
