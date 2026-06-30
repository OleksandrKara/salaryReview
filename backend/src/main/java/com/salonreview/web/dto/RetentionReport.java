package com.salonreview.web.dto;

import java.math.BigDecimal;
import java.util.List;

/** Per-provider retention/effectiveness for a month, plus a short trend per provider. */
public record RetentionReport(int year, int month, int retentionWindowDays, List<ProviderRetentionRow> providers) {

    public record ProviderRetentionRow(
            String providerRef,
            String providerName,
            /** Distinct customers this provider served this month. */
            int clientsSeen,
            /** Of those, first-ever visit with this provider was this month. */
            int newToProvider,
            /** Seen this provider before this month. */
            int returningToProvider,
            /** First-ever salon visit was this month AND it was with this provider (fresh client acquired). */
            int newToSalonViaP,
            /** Share of this month's visits where the customer booked a future appointment that same day (0..1); null when no visits. */
            BigDecimal sameDayRebookRate,
            /** Size of this month's new-to-provider cohort (the basis for the retention rates). */
            int cohortSize,
            /** Of the cohort, share that returned to THIS provider within the window (0..1); null when immature/empty. */
            BigDecimal providerRetention,
            /** Of the cohort, share that returned to the SALON (any provider) within the window; null when immature/empty. */
            BigDecimal salonRetention,
            /** True once the window has fully elapsed for this cohort (else the rates are "too soon"). */
            boolean cohortMatured,
            /** Many fresh clients acquired but matured retention is low — the "we give them new clients, they don't return" risk. */
            boolean leakRisk,
            /** Last few months: clientele size + new-to-provider, for a sparkline. */
            List<RetentionTrendPoint> trend
    ) {}

    public record RetentionTrendPoint(int year, int month, int clientsSeen, int newToProvider) {}
}
