package com.salonreview.web.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * A manager's own timesheet for one month, split into the salon's half-month pay periods. {@code
 * usdPerHour} and the {@code *Pay} figures are null when the owner hasn't set the manager's rate yet
 * (the UI then shows hours only with a "rate not set" hint). Minutes are always populated.
 */
public record ManagerTimesheetDto(
        int year,
        int month,
        String timezone,
        BigDecimal usdPerHour,        // null = rate not set
        int firstMinutes,
        int secondMinutes,
        int monthMinutes,
        BigDecimal firstPay,          // null when rate unset
        BigDecimal secondPay,
        BigDecimal monthPay,
        List<TimeEntryDto> entries,   // all shifts this month, start-time ascending
        TimeEntryDto open             // the currently-open shift, or null
) {}
