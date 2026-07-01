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
            boolean finalized,
            /** Distinct clients seen this month (from the visit ledger); 0 when unknown. */
            int clientsSeen,
            /** Of those, clients who had visited the salon before this month (returning); 0 when unknown. */
            int returningClients
    ) {
        /** Copy with the visit-ledger client counts filled in. */
        public MonthSummary withClients(int seen, int returning) {
            return new MonthSummary(year, month, label, cardRevenue, cashRevenue, grossRevenue, tips,
                    procedures, avgPerAppt, payrollCost, payrollPct, finalized, seen, returning);
        }
    }

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
