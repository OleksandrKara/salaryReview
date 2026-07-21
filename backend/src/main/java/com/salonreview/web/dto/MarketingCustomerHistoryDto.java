package com.salonreview.web.dto;

import java.util.List;

/** One Square customer's submission + appointment history, fetched on demand when the owner
 * expands a row on the Ads Report breakdown drill-down (see MarketingAdsReportController) — the
 * same data ContactsTable.tsx already shows per contact, just reachable by Square customer id
 * instead of by contact row, since the breakdown's completed/upcoming rows only carry the former.
 */
public record MarketingCustomerHistoryDto(
        List<MarketingContactDto.Submission> submissions,
        List<MarketingContactDto.Appointment> appointments
) {
    public static MarketingCustomerHistoryDto empty() {
        return new MarketingCustomerHistoryDto(List.of(), List.of());
    }
}
