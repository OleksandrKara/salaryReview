package com.salonreview.marketing;

import com.salonreview.marketing.FunnelAnalyticsRepository.RawFunnelStep;
import com.salonreview.marketing.FunnelAnalyticsRepository.VariantMeta;
import com.salonreview.square.SquareClient;
import com.salonreview.web.dto.FunnelDashboardDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FunnelAnalyticsServiceTest {

    private FunnelAnalyticsRepository repository;
    private MarketingDashboardRepository landingPageRepository;
    private FunnelAnalyticsService service;

    private static final UUID LANDING_PAGE_ID = UUID.randomUUID();
    private static final UUID VARIANT_A = UUID.randomUUID();
    private static final UUID VARIANT_B = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(FunnelAnalyticsRepository.class);
        landingPageRepository = mock(MarketingDashboardRepository.class);
        SquareClient square = mock(SquareClient.class);
        when(square.locationTimeZone()).thenReturn("America/Los_Angeles");
        com.salonreview.square.SquareClientProvider squareClientProvider =
                mock(com.salonreview.square.SquareClientProvider.class);
        when(squareClientProvider.forBusiness(org.mockito.ArgumentMatchers.anyLong())).thenReturn(square);
        com.salonreview.config.CurrentBusinessContext currentBusinessContext =
                mock(com.salonreview.config.CurrentBusinessContext.class);
        when(currentBusinessContext.id()).thenReturn(1L);
        service = new FunnelAnalyticsService(repository, landingPageRepository, squareClientProvider,
                currentBusinessContext);

        when(landingPageRepository.findLandingPageId("home", 1L)).thenReturn(Optional.of(LANDING_PAGE_ID));
        when(landingPageRepository.findStatsSince(LANDING_PAGE_ID)).thenReturn(Optional.empty());
        // Default: no activity recorded for anyone, no meta known — each test overrides what it needs.
        when(repository.findLastActivityByVariant(LANDING_PAGE_ID)).thenReturn(Map.of());
        when(repository.findVariantMeta(LANDING_PAGE_ID)).thenReturn(Map.of());
        when(repository.countPageViewsByVariant(any(), any(), any(), any())).thenReturn(Map.of());
        when(repository.countBookingsCompletedByVariant(any(), any(), any(), any())).thenReturn(Map.of());
    }

    @Test
    @DisplayName("computes reached %, drop-off count/%, and final conversion rate across a 4-step funnel")
    void computesDropOffMath() {
        when(repository.findFunnelSteps(eq(LANDING_PAGE_ID), isNull(), isNull(), eq(TrafficSourceSql.ADS_ONLY))).thenReturn(List.of(
                new RawFunnelStep(VARIANT_A, "homepage_booking_v1", "services", 0, 4, 100),
                new RawFunnelStep(VARIANT_A, "homepage_booking_v1", "addons", 1, 4, 80),
                new RawFunnelStep(VARIANT_A, "homepage_booking_v1", "datetime", 2, 4, 50),
                new RawFunnelStep(VARIANT_A, "homepage_booking_v1", "details", 3, 4, 30)
        ));
        when(repository.findVariantMeta(LANDING_PAGE_ID)).thenReturn(Map.of(VARIANT_A, new VariantMeta("Control", "control", 100, true)));
        when(repository.countPageViewsByVariant(LANDING_PAGE_ID, null, null, TrafficSourceSql.ADS_ONLY)).thenReturn(Map.of(VARIANT_A, 1000L));
        when(repository.countBookingsCompletedByVariant(LANDING_PAGE_ID, null, null, TrafficSourceSql.ADS_ONLY)).thenReturn(Map.of(VARIANT_A, 25L));

        List<FunnelDashboardDto> result = service.funnel("home", TrafficSourceSql.ADS_ONLY, null, null);

        assertThat(result).hasSize(1);
        FunnelDashboardDto dto = result.get(0);
        assertThat(dto.variantId()).isEqualTo(VARIANT_A);
        assertThat(dto.variantName()).isEqualTo("Control");
        assertThat(dto.variantKey()).isEqualTo("control");
        assertThat(dto.variantWeight()).isEqualTo(100);
        assertThat(dto.variantEnabled()).isTrue();
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
        when(repository.findFunnelSteps(eq(LANDING_PAGE_ID), isNull(), isNull(), eq(TrafficSourceSql.ADS_ONLY))).thenReturn(List.of(
                new RawFunnelStep(VARIANT_A, "homepage_booking_v1", "services", 0, 4, 0)
        ));
        when(repository.findVariantMeta(LANDING_PAGE_ID)).thenReturn(Map.of(VARIANT_A, new VariantMeta("Control", "control", 100, true)));

        List<FunnelDashboardDto> result = service.funnel("home", TrafficSourceSql.ADS_ONLY, null, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).finalConversionRate()).isEqualTo(0.0);
        assertThat(result.get(0).steps().get(0).reachedPctOfStarted()).isEqualTo(0.0);
        assertThat(result.get(0).steps().get(0).dropOffPct()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("returns an empty list when the requested slug has no landing page")
    void emptyWhenSlugNotFound() {
        when(landingPageRepository.findLandingPageId("unknown-slug", 1L)).thenReturn(Optional.empty());

        assertThat(service.funnel("unknown-slug", TrafficSourceSql.ADS_ONLY, null, null)).isEmpty();
    }

    @Test
    @DisplayName("returns an empty list when no funnel events have been recorded yet")
    void emptyWhenNoFunnelEvents() {
        when(repository.findFunnelSteps(eq(LANDING_PAGE_ID), isNull(), isNull(), eq(TrafficSourceSql.ADS_ONLY))).thenReturn(List.of());

        assertThat(service.funnel("home", TrafficSourceSql.ADS_ONLY, null, null)).isEmpty();
    }

    @Test
    @DisplayName("returns an empty list, not a thrown exception, when the marketing schema is unreachable")
    void emptyWhenRepositoryThrows() {
        when(landingPageRepository.findLandingPageId("home", 1L))
                .thenThrow(new DataAccessResourceFailureException("relation \"marketing.landing_pages\" does not exist"));

        assertThat(service.funnel("home", TrafficSourceSql.ADS_ONLY, null, null)).isEmpty();
    }

    @Test
    @DisplayName("two variants sharing the exact same flow_key each get their own row, not a pooled total — "
            + "this is the whole point of keying by variant instead of by flow_key")
    void sameFlowKeyDifferentVariantsGetSeparateRows() {
        when(repository.findFunnelSteps(eq(LANDING_PAGE_ID), isNull(), isNull(), eq(TrafficSourceSql.ADS_ONLY))).thenReturn(List.of(
                new RawFunnelStep(VARIANT_A, "mani_booking_v2", "services", 0, 4, 100),
                new RawFunnelStep(VARIANT_B, "mani_booking_v2", "services", 0, 4, 40)
        ));
        when(repository.findVariantMeta(LANDING_PAGE_ID)).thenReturn(Map.of(
                VARIANT_A, new VariantMeta("Version_7", "version-7", 50, true),
                VARIANT_B, new VariantMeta("Precision Studio", "mani-precision", 50, true)));
        when(repository.countPageViewsByVariant(any(), any(), any(), any())).thenReturn(Map.of(VARIANT_A, 500L, VARIANT_B, 500L));
        when(repository.countBookingsCompletedByVariant(any(), any(), any(), any())).thenReturn(Map.of(VARIANT_A, 10L, VARIANT_B, 4L));

        List<FunnelDashboardDto> result = service.funnel("home", TrafficSourceSql.ADS_ONLY, null, null);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(FunnelDashboardDto::flowKey).containsExactly("mani_booking_v2", "mani_booking_v2");
        assertThat(result).extracting(FunnelDashboardDto::variantName).containsExactlyInAnyOrder("Version_7", "Precision Studio");
        var versionSeven = result.stream().filter(d -> d.variantName().equals("Version_7")).findFirst().orElseThrow();
        var precision = result.stream().filter(d -> d.variantName().equals("Precision Studio")).findFirst().orElseThrow();
        assertThat(versionSeven.totalStarted()).isEqualTo(100);
        assertThat(versionSeven.totalCompleted()).isEqualTo(10);
        assertThat(precision.totalStarted()).isEqualTo(40);
        assertThat(precision.totalCompleted()).isEqualTo(4);
    }

    @Test
    @DisplayName("a variant with a recent event (within 7 days) is active; one gone quiet for longer is not — "
            + "so a retired variant's old data reads as history, not as if it were still the live test")
    void distinguishesActiveFromRetiredVariantsByRecentActivity() {
        when(repository.findFunnelSteps(eq(LANDING_PAGE_ID), isNull(), isNull(), eq(TrafficSourceSql.ADS_ONLY))).thenReturn(List.of(
                new RawFunnelStep(VARIANT_A, "mani_booking_v1", "contact", 0, 4, 219),
                new RawFunnelStep(VARIANT_B, "mani_booking_v2", "services", 0, 4, 82)
        ));
        when(repository.findVariantMeta(LANDING_PAGE_ID)).thenReturn(Map.of(
                VARIANT_A, new VariantMeta("Version_1", "version-1", 0, true),
                VARIANT_B, new VariantMeta("Version_7", "version-7", 100, true)));
        when(repository.countPageViewsByVariant(any(), any(), any(), any())).thenReturn(Map.of(VARIANT_A, 500L, VARIANT_B, 500L));
        when(repository.countBookingsCompletedByVariant(any(), any(), any(), any())).thenReturn(Map.of(VARIANT_A, 10L, VARIANT_B, 10L));
        Instant retiredLastSeen = Instant.now().minus(30, ChronoUnit.DAYS);
        Instant liveLastSeen = Instant.now().minus(1, ChronoUnit.HOURS);
        when(repository.findLastActivityByVariant(LANDING_PAGE_ID)).thenReturn(Map.of(
                VARIANT_A, retiredLastSeen,
                VARIANT_B, liveLastSeen));

        List<FunnelDashboardDto> result = service.funnel("home", TrafficSourceSql.ADS_ONLY, null, null);

        var v1 = result.stream().filter(d -> d.variantId().equals(VARIANT_A)).findFirst().orElseThrow();
        var v7 = result.stream().filter(d -> d.variantId().equals(VARIANT_B)).findFirst().orElseThrow();
        assertThat(v1.active()).isFalse();
        assertThat(v1.lastActivityAt()).isEqualTo(retiredLastSeen);
        assertThat(v7.active()).isTrue();
        assertThat(v7.lastActivityAt()).isEqualTo(liveLastSeen);
        // Active-first, then descending weight — a stable, meaningful default order.
        assertThat(result).extracting(FunnelDashboardDto::variantId).containsExactly(VARIANT_B, VARIANT_A);
    }

    @Test
    @DisplayName("a variant with no recorded activity at all (shouldn't happen, but no crash) is inactive with a null lastActivityAt")
    void variantWithNoActivityRecordIsInactive() {
        when(repository.findFunnelSteps(eq(LANDING_PAGE_ID), isNull(), isNull(), eq(TrafficSourceSql.ADS_ONLY))).thenReturn(List.of(
                new RawFunnelStep(VARIANT_A, "homepage_booking_v1", "services", 0, 4, 100)
        ));
        when(repository.findVariantMeta(LANDING_PAGE_ID)).thenReturn(Map.of(VARIANT_A, new VariantMeta("Control", "control", 100, true)));
        when(repository.countPageViewsByVariant(any(), any(), any(), any())).thenReturn(Map.of(VARIANT_A, 500L));
        when(repository.countBookingsCompletedByVariant(any(), any(), any(), any())).thenReturn(Map.of(VARIANT_A, 10L));

        List<FunnelDashboardDto> result = service.funnel("home", TrafficSourceSql.ADS_ONLY, null, null);

        assertThat(result.get(0).active()).isFalse();
        assertThat(result.get(0).lastActivityAt()).isNull();
    }

    @Test
    @DisplayName("a variant_id with funnel data but no matching landing_variants row is skipped, not shown nameless")
    void variantWithNoMetaIsSkipped() {
        when(repository.findFunnelSteps(eq(LANDING_PAGE_ID), isNull(), isNull(), eq(TrafficSourceSql.ADS_ONLY))).thenReturn(List.of(
                new RawFunnelStep(VARIANT_A, "homepage_booking_v1", "services", 0, 4, 100)
        ));
        // findVariantMeta deliberately left empty (default from setUp) — VARIANT_A has no meta.

        assertThat(service.funnel("home", TrafficSourceSql.ADS_ONLY, null, null)).isEmpty();
    }
}
