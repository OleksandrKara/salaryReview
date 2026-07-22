package com.salonreview.web;

import com.salonreview.marketing.MarketingAnalyticsService;
import com.salonreview.marketing.MarketingAnalyticsService.PeriodKind;
import com.salonreview.marketing.MarketingContactsService;
import com.salonreview.web.dto.MarketingAdsReportDto;
import com.salonreview.web.dto.MarketingAdsReportDto.PeriodRow;
import com.salonreview.web.dto.MarketingContactDto.Contact;
import com.salonreview.web.dto.MarketingCustomerHistoryDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone {@code MockMvc} — only the {@code period} param parsing (week/mtd/month/custom
 * and their from/to defaulting) lives in the controller; the report logic itself is covered by
 * {@code MarketingAnalyticsServiceTest}. Role gating (OWNER+ADS_MANAGER GET) is enforced by
 * {@code SecurityConfig} and covered transitively, not re-tested here — see {@code RagControllerTest}.
 */
class MarketingAdsReportControllerTest {

    private MarketingAnalyticsService service;
    private MarketingContactsService contactsService;
    private MockMvc mvc;

    private static final PeriodRow EMPTY_ROW = new PeriodRow(
            LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31),
            BigDecimal.ZERO, false, BigDecimal.ZERO, BigDecimal.ZERO, 0, BigDecimal.ZERO, 0, 0, false);
    private static final MarketingAdsReportDto EMPTY_DTO =
            new MarketingAdsReportDto("WEEK", List.of(), EMPTY_ROW);

    @BeforeEach
    void setUp() {
        service = mock(MarketingAnalyticsService.class);
        contactsService = mock(MarketingContactsService.class);
        when(service.adsReport(any(), any(), any(), any(), any())).thenReturn(EMPTY_DTO);
        MarketingAdsReportController controller = new MarketingAdsReportController(service, contactsService);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("no period param defaults to WEEK")
    void defaultsToWeek() throws Exception {
        mvc.perform(get("/api/owner/marketing/ads-report")).andExpect(status().isOk());

        verify(service).adsReport(any(), any(), any(), isNull(), eq(PeriodKind.WEEK));
    }

    @Test
    @DisplayName("period=month maps to PeriodKind.MONTH")
    void periodMonth() throws Exception {
        mvc.perform(get("/api/owner/marketing/ads-report").param("period", "month").param("slug", "mani"))
                .andExpect(status().isOk());

        verify(service).adsReport(any(), any(), any(), eq("mani"), eq(PeriodKind.MONTH));
    }

    @Test
    @DisplayName("period=mtd ignores from/to and always uses [1st-of-current-month, today]")
    void periodMonthToDateIgnoresFromTo() throws Exception {
        mvc.perform(get("/api/owner/marketing/ads-report")
                        .param("period", "mtd")
                        .param("from", "2020-01-01")
                        .param("to", "2020-01-02")
                        .param("slug", "mani"))
                .andExpect(status().isOk());

        LocalDate today = LocalDate.now();
        verify(service).adsReport(
                eq(today.withDayOfMonth(1)), eq(today), any(), eq("mani"), eq(PeriodKind.MONTH_TO_DATE));
    }

    @Test
    @DisplayName("period=custom uses the explicit from/to verbatim, with no calendar alignment")
    void periodCustomUsesExplicitRange() throws Exception {
        mvc.perform(get("/api/owner/marketing/ads-report")
                        .param("period", "custom")
                        .param("from", "2026-07-05")
                        .param("to", "2026-07-19")
                        .param("slug", "mani"))
                .andExpect(status().isOk());

        verify(service).adsReport(eq(LocalDate.of(2026, 7, 5)), eq(LocalDate.of(2026, 7, 19)),
                any(), eq("mani"), eq(PeriodKind.CUSTOM));
    }

    @Test
    @DisplayName("period=custom without both from and to is rejected — a caller-specified range is the whole point")
    void periodCustomRequiresFromAndTo() throws Exception {
        mvc.perform(get("/api/owner/marketing/ads-report").param("period", "custom").param("from", "2026-07-05"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("sources param is parsed and threaded through, unlike slug it has no default null short-circuit")
    void sourcesParamIsParsed() throws Exception {
        mvc.perform(get("/api/owner/marketing/ads-report").param("sources", "meta_ads,google_ads"))
                .andExpect(status().isOk());

        verify(service).adsReport(any(), any(), eq(Set.of("meta_ads", "google_ads")), isNull(), eq(PeriodKind.WEEK));
    }

    @Test
    @DisplayName("GET /customer-history returns that customer's submissions and appointments when a contact resolves")
    void customerHistoryReturnsResolvedContact() throws Exception {
        Contact contact = new Contact(
                "contact-1",     // id
                "Jane",          // givenName
                "+16195550001",  // phoneNumber
                null,            // emailAddress
                null,            // originalTrafficSource
                null,            // marketingTrafficSource
                null,            // channel
                null,            // utmSource
                null,            // utmMedium
                null,            // utmCampaign
                "mani",          // landingPageSlug
                null,            // variantName
                null,            // deviceType
                null,            // osName
                null,            // osVersion
                null,            // browserName
                null,            // browserVersion
                null,            // smsMarketingConsent
                null,            // emailMarketingConsent
                null,            // squareProfileUrl
                List.of(),       // submissions
                List.of(),       // appointments
                null,            // createdAt
                null);           // updatedAt
        when(contactsService.contactByCustomerId("cust-1")).thenReturn(java.util.Optional.of(contact));

        mvc.perform(get("/api/owner/marketing/ads-report/customer-history").param("customerId", "cust-1"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.submissions").isArray())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.appointments").isArray());
    }

    @Test
    @DisplayName("GET /customer-history returns empty lists (not an error) when no contact resolves to this customer")
    void customerHistoryReturnsEmptyWhenUnresolved() throws Exception {
        when(contactsService.contactByCustomerId("cust-unknown")).thenReturn(java.util.Optional.empty());

        mvc.perform(get("/api/owner/marketing/ads-report/customer-history").param("customerId", "cust-unknown"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.submissions").isEmpty())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.appointments").isEmpty());
    }
}
