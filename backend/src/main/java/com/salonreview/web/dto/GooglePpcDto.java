package com.salonreview.web.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

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
        List<PageResults> pages,
        List<PayingCustomer> customers,
        Counts unattributed,
        Map<String, Double> googleByEvent
) {
    public record Results(long visitors, long smsTaps, long callTaps, long consultations, long deposits,
                          long fullPayments, long payingCustomers, BigDecimal revenue, long forms, long ringostatCalls, long smsReceived) {}

    /** Conversions that could not be tied to one of this period's campaigns (older campaign, no campaign recorded). */
    public record Counts(long smsTaps, long callTaps, long consultations, long forms, long ringostatCalls, long smsReceived) {}

    public record Campaign(long campaignId, String name, String status, BigDecimal spend, long impressions,
                           long clicks, double platformConversions, long smsTaps, long callTaps, long consultations,
                           long forms, long ringostatCalls, long smsReceived, Map<String, Double> googleByEvent) {}

    public record PageResults(String slug, String name, long visitors, long smsTaps, long callTaps,
                              long consultations, long deposits, long fullPayments, BigDecimal revenue, long forms, long smsReceived) {}

    public record Payment(java.time.Instant at, BigDecimal amount) {}

    public record PayingCustomer(String customerId, String name, String phone, String email,
                                 java.time.Instant firstContactAt, String pageSlug, String campaign,
                                 String service, String bookingStatus, java.time.Instant bookingStartAt,
                                 String artist, BigDecimal total, boolean deposit, boolean full,
                                 List<Payment> payments) {}
}
