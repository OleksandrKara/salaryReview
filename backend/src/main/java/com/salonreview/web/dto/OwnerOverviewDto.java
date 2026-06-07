package com.salonreview.web.dto;

import java.math.BigDecimal;
import java.util.List;

public record OwnerOverviewDto(
        int fromYear,
        int fromMonth,
        int toYear,
        int toMonth,
        List<MonthSummary> months,
        List<ProviderYtd> providers,
        YearTotals prevYear
) {
    public record MonthSummary(
            int year,
            int month,
            String label,
            BigDecimal cardRevenue,
            BigDecimal cashRevenue,
            BigDecimal grossRevenue,
            BigDecimal tips,
            int procedures,
            BigDecimal avgPerAppt,
            BigDecimal payrollCost,
            BigDecimal payrollPct,
            boolean finalized
    ) {}

    public record ProviderYtd(
            Long providerId,
            String name,
            BigDecimal ytdGross,
            BigDecimal ytdPayroll,
            BigDecimal ytdPayrollPct
    ) {}

    public record YearTotals(
            BigDecimal totalGross,
            BigDecimal totalCard,
            BigDecimal totalCash
    ) {}
}
