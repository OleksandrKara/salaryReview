package com.salonreview.marketing;

import com.salonreview.marketing.FunnelAnalyticsRepository.RawFunnelStep;
import com.salonreview.web.dto.FunnelDashboardDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FunnelAnalyticsServiceTest {

    private FunnelAnalyticsRepository repository;
    private MarketingDashboardRepository landingPageRepository;
    private FunnelAnalyticsService service;

    private static final UUID LANDING_PAGE_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(FunnelAnalyticsRepository.class);
        landingPageRepository = mock(MarketingDashboardRepository.class);
        service = new FunnelAnalyticsService(repository, landingPageRepository);
    }

    @Test
    @DisplayName("computes reached %, drop-off count/%, and final conversion rate across a 4-step funnel")
    void computesDropOffMath() {
        when(landingPageRepository.findLandingPageId("home")).thenReturn(Optional.of(LANDING_PAGE_ID));
        when(landingPageRepository.findStatsSince(LANDING_PAGE_ID)).thenReturn(Optional.empty());
        when(repository.findFunnelSteps(eq(LANDING_PAGE_ID), isNull(), eq(true))).thenReturn(List.of(
                new RawFunnelStep("homepage_booking_v1", "services", 0, 4, 100),
                new RawFunnelStep("homepage_booking_v1", "addons", 1, 4, 80),
                new RawFunnelStep("homepage_booking_v1", "datetime", 2, 4, 50),
                new RawFunnelStep("homepage_booking_v1", "details", 3, 4, 30)
        ));
        when(repository.countPageViews(LANDING_PAGE_ID, null, true)).thenReturn(1000L);
        when(repository.countBookingsCompleted(LANDING_PAGE_ID, null, true)).thenReturn(25L);

        List<FunnelDashboardDto> result = service.funnel("home", true);

        assertThat(result).hasSize(1);
        FunnelDashboardDto dto = result.get(0);
        assertThat(dto.flowKey()).isEqualTo("homepage_booking_v1");
        assertThat(dto.totalVisitors()).isEqualTo(1000);
        assertThat(dto.totalStarted()).isEqualTo(100);
        assertThat(dto.totalCompleted()).isEqualTo(25);
        assertThat(dto.finalConversionRate()).isEqualTo(0.025);

        assertThat(dto.steps()).hasSize(4);
        var services = dto.steps().get(0);
        assertThat(services.reachedCount()).isEqualTo(100);
        assertThat(services.reachedPctOfStarted()).isEqualTo(1.0);
        assertThat(services.dropOffCount()).isEqualTo(0);
        assertThat(services.dropOffPct()).isEqualTo(0.0);

        var addons = dto.steps().get(1);
        assertThat(addons.reachedCount()).isEqualTo(80);
        assertThat(addons.reachedPctOfStarted()).isEqualTo(0.8);
        assertThat(addons.dropOffCount()).isEqualTo(20);
        assertThat(addons.dropOffPct()).isEqualTo(0.2);

        var datetime = dto.steps().get(2);
        assertThat(datetime.dropOffCount()).isEqualTo(30);
        assertThat(datetime.dropOffPct()).isEqualTo(0.375);

        var details = dto.steps().get(3);
        assertThat(details.reachedCount()).isEqualTo(30);
        assertThat(details.dropOffCount()).isEqualTo(20);
    }

    @Test
    @DisplayName("returns an empty list, not a division error, when totalStarted/totalVisitors are zero")
    void zeroDenominatorsYieldZeroRatesNotCrash() {
        when(landingPageRepository.findLandingPageId("home")).thenReturn(Optional.of(LANDING_PAGE_ID));
        when(landingPageRepository.findStatsSince(LANDING_PAGE_ID)).thenReturn(Optional.empty());
        when(repository.findFunnelSteps(eq(LANDING_PAGE_ID), isNull(), eq(true))).thenReturn(List.of(
                new RawFunnelStep("homepage_booking_v1", "services", 0, 4, 0)
        ));
        when(repository.countPageViews(LANDING_PAGE_ID, null, true)).thenReturn(0L);
        when(repository.countBookingsCompleted(LANDING_PAGE_ID, null, true)).thenReturn(0L);

        List<FunnelDashboardDto> result = service.funnel("home", true);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).finalConversionRate()).isEqualTo(0.0);
        assertThat(result.get(0).steps().get(0).reachedPctOfStarted()).isEqualTo(0.0);
        assertThat(result.get(0).steps().get(0).dropOffPct()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("returns an empty list when the requested slug has no landing page")
    void emptyWhenSlugNotFound() {
        when(landingPageRepository.findLandingPageId("unknown-slug")).thenReturn(Optional.empty());

        assertThat(service.funnel("unknown-slug", true)).isEmpty();
    }

    @Test
    @DisplayName("returns an empty list when no funnel events have been recorded yet")
    void emptyWhenNoFunnelEvents() {
        when(landingPageRepository.findLandingPageId("home")).thenReturn(Optional.of(LANDING_PAGE_ID));
        when(landingPageRepository.findStatsSince(LANDING_PAGE_ID)).thenReturn(Optional.empty());
        when(repository.findFunnelSteps(eq(LANDING_PAGE_ID), isNull(), eq(true))).thenReturn(List.of());

        assertThat(service.funnel("home", true)).isEmpty();
    }

    @Test
    @DisplayName("returns an empty list, not a thrown exception, when the marketing schema is unreachable")
    void emptyWhenRepositoryThrows() {
        when(landingPageRepository.findLandingPageId("home"))
                .thenThrow(new DataAccessResourceFailureException("relation \"marketing.landing_pages\" does not exist"));

        assertThat(service.funnel("home", true)).isEmpty();
    }

    @Test
    @DisplayName("groups multiple flow_keys for the same landing page into separate funnels")
    void groupsMultipleFlowKeysSeparately() {
        when(landingPageRepository.findLandingPageId("home")).thenReturn(Optional.of(LANDING_PAGE_ID));
        when(landingPageRepository.findStatsSince(LANDING_PAGE_ID)).thenReturn(Optional.empty());
        when(repository.findFunnelSteps(eq(LANDING_PAGE_ID), isNull(), eq(true))).thenReturn(List.of(
                new RawFunnelStep("homepage_booking_v1", "services", 0, 4, 100),
                new RawFunnelStep("homepage_booking_v2", "services", 0, 3, 40)
        ));
        when(repository.countPageViews(any(), any(), anyBoolean())).thenReturn(500L);
        when(repository.countBookingsCompleted(any(), any(), anyBoolean())).thenReturn(10L);

        List<FunnelDashboardDto> result = service.funnel("home", true);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(FunnelDashboardDto::flowKey)
                .containsExactly("homepage_booking_v1", "homepage_booking_v2");
    }
}
