package com.salonreview.marketing;

import com.salonreview.domain.SalonConfig;
import com.salonreview.repo.SalonConfigRepository;
import com.salonreview.square.SquareMonthAggregator;
import com.salonreview.square.SquareMonthAggregator.AttributedService;
import com.salonreview.square.SquareMonthAggregator.MonthAggregation;
import com.salonreview.web.dto.MarketingAnalyticsDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MarketingAnalyticsServiceTest {

    private MarketingContactsRepository contactsRepository;
    private SquareMonthAggregator aggregator;
    private SalonConfigRepository salonConfig;
    private MarketingAnalyticsService service;

    @BeforeEach
    void setUp() {
        contactsRepository = mock(MarketingContactsRepository.class);
        aggregator = mock(SquareMonthAggregator.class);
        salonConfig = mock(SalonConfigRepository.class);
        when(salonConfig.findById(1)).thenReturn(Optional.of(SalonConfig.builder()
                .id(1).ownerShortName("o").servicePriceCutoff(new BigDecimal("60.00")).build()));
        service = new MarketingAnalyticsService(contactsRepository, aggregator, salonConfig);
    }

    private static AttributedService svc(String date, String customerId, String gross) {
        return new AttributedService("p1", "P", date, "FIRST", "Manicure", new BigDecimal(gross),
                BigDecimal.ZERO, new BigDecimal(gross), BigDecimal.ZERO, true, 1, 1, false, "CARD",
                null, "booking-1", customerId, "Customer");
    }

    private static MonthAggregation aggOf(int year, int month, List<AttributedService> services) {
        return new MonthAggregation(year, month, "UTC", List.of(), new SquareMonthAggregator.Diag(),
                services, List.of(), List.of());
    }

    @Test
    @DisplayName("sums gross and counts distinct customers/services for ads-attributed customers only, within range")
    void aggregatesAdsAttributedServicesInRange() {
        when(contactsRepository.findAdsAttributedSquareCustomerIds()).thenReturn(Set.of("cust-ads-1", "cust-ads-2"));
        when(aggregator.aggregate(2026, 7, new BigDecimal("60.00"))).thenReturn(aggOf(2026, 7, List.of(
                svc("2026-07-05", "cust-ads-1", "93.00"),
                svc("2026-07-10", "cust-ads-1", "45.00"), // same customer, second visit
                svc("2026-07-15", "cust-ads-2", "85.00"),
                svc("2026-07-20", "cust-organic", "70.00") // not ads-attributed — excluded
        )));

        MarketingAnalyticsDto dto = service.analytics(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));

        assertThat(dto.customerCount()).isEqualTo(2);
        assertThat(dto.serviceCount()).isEqualTo(3);
        assertThat(dto.grossRevenue()).isEqualByComparingTo("223.00");
    }

    @Test
    @DisplayName("excludes services outside the requested date range even within the same month")
    void excludesServicesOutsideDateRange() {
        when(contactsRepository.findAdsAttributedSquareCustomerIds()).thenReturn(Set.of("cust-ads-1"));
        when(aggregator.aggregate(2026, 7, new BigDecimal("60.00"))).thenReturn(aggOf(2026, 7, List.of(
                svc("2026-07-05", "cust-ads-1", "93.00"),
                svc("2026-07-20", "cust-ads-1", "85.00")
        )));

        MarketingAnalyticsDto dto = service.analytics(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 10));

        assertThat(dto.serviceCount()).isEqualTo(1);
        assertThat(dto.grossRevenue()).isEqualByComparingTo("93.00");
    }

    @Test
    @DisplayName("spans multiple calendar months for a custom range crossing a month boundary")
    void spansMultipleMonths() {
        when(contactsRepository.findAdsAttributedSquareCustomerIds()).thenReturn(Set.of("cust-ads-1"));
        when(aggregator.aggregate(2026, 6, new BigDecimal("60.00"))).thenReturn(aggOf(2026, 6, List.of(
                svc("2026-06-28", "cust-ads-1", "93.00")
        )));
        when(aggregator.aggregate(2026, 7, new BigDecimal("60.00"))).thenReturn(aggOf(2026, 7, List.of(
                svc("2026-07-02", "cust-ads-1", "85.00")
        )));

        MarketingAnalyticsDto dto = service.analytics(LocalDate.of(2026, 6, 25), LocalDate.of(2026, 7, 5));

        assertThat(dto.serviceCount()).isEqualTo(2);
        assertThat(dto.grossRevenue()).isEqualByComparingTo("178.00");
    }

    @Test
    @DisplayName("short-circuits with zeroed results, skipping Square entirely, when no contact is ads-attributed")
    void shortCircuitsWhenNoAdsAttributedContacts() {
        when(contactsRepository.findAdsAttributedSquareCustomerIds()).thenReturn(Set.of());

        MarketingAnalyticsDto dto = service.analytics(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));

        assertThat(dto.customerCount()).isZero();
        assertThat(dto.serviceCount()).isZero();
        assertThat(dto.grossRevenue()).isEqualByComparingTo("0.00");
        org.mockito.Mockito.verifyNoInteractions(aggregator);
    }
}
