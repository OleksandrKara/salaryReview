package com.salonreview.web.dto;

import java.util.List;

/**
 * New-vs-returning client counts over a month range, for the whole salon (providerRef null) or one
 * provider. Drives the retention chart + provider selector. "New" is new-to-salon for the all-provider
 * view and new-to-provider for a single provider; "returning" is the rest seen that month.
 */
public record RetentionSeries(
        int fromYear, int fromMonth, int toYear, int toMonth,
        String providerRef,                 // null = all providers (salon-level)
        List<ProviderOption> providers,     // for the dropdown
        List<SeriesPoint> points) {

    public record ProviderOption(String ref, String name) {}

    public record SeriesPoint(int year, int month, int clientsSeen, int newClients, int returningClients) {}
}
