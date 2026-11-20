package com.salonreview.web.dto;

import java.math.BigDecimal;
import java.util.List;

/** Google Ads results for the whole business: spend from the daily sync, outcomes from visitors who
 * arrived with a Google click on any landing page. */
public record GooglePpcDto(
        boolean hasSpend,
        BigDecimal spend,
        long impressions,
        long clicks,
        double platformConversions,
        Results results,
        List<Campaign> campaigns,
        List<PageResults> pages
) {
    public record Results(long visitors, long smsTaps, long callTaps, long consultations, long deposits,
                          long fullPayments, long payingCustomers, BigDecimal revenue) {}

    public record Campaign(long campaignId, String name, String status, BigDecimal spend, long impressions,
                           long clicks, double platformConversions) {}

    public record PageResults(String slug, String name, long visitors, long smsTaps, long callTaps,
                              long consultations, long deposits, long fullPayments, BigDecimal revenue) {}
}
