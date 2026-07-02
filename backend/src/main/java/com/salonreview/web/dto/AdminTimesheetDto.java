package com.salonreview.web.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * The owner's payroll view of every manager's time for one calendar month. One {@link Row} per active
 * manager (including those with no hours yet, so the owner can set a rate).
 */
public record AdminTimesheetDto(
        int year,
        int month,
        String timezone,
        List<Row> managers
) {
    public record Row(
            Long userId,
            String username,
            String email,
            BigDecimal usdPerHour,   // null = rate not set
            int monthMinutes,
            BigDecimal monthPay,     // null when rate unset
            boolean clockedIn        // true if currently on an open shift
    ) {}
}
