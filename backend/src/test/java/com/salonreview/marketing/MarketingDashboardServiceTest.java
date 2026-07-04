package com.salonreview.marketing;

import com.salonreview.marketing.MarketingDashboardRepository.RawVariantStat;
import com.salonreview.web.dto.MarketingDashboardDto;
import com.salonreview.web.dto.MarketingDashboardDto.VariantStat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MarketingDashboardServiceTest {

    private MarketingDashboardRepository repository;
    private MarketingDashboardService service;

    private static final UUID LANDING_PAGE_ID = UUID.randomUUID();
    private static final UUID VARIANT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(MarketingDashboardRepository.class);
        service = new MarketingDashboardService(repository);
    }

    @Test
    @DisplayName("computes conversion rate from page views and completed bookings")
    void computesConversionRate() {
        when(repository.findLandingPageId("mani")).thenReturn(Optional.of(LANDING_PAGE_ID));
        when(repository.findExperimentStatus(LANDING_PAGE_ID)).thenReturn(Optional.of("active"));
        when(repository.findVariantStats(LANDING_PAGE_ID)).thenReturn(List.of(
                new RawVariantStat(VARIANT_ID.toString(), "Control", 20, true, 100, 25)
        ));

        MarketingDashboardDto dashboard = service.dashboard("mani");

        assertThat(dashboard.available()).isTrue();
        assertThat(dashboard.experimentStatus()).isEqualTo("active");
        VariantStat variant = dashboard.variants().get(0);
        assertThat(variant.pageViews()).isEqualTo(100);
        assertThat(variant.bookingsCompleted()).isEqualTo(25);
        assertThat(variant.conversionRate()).isEqualTo(0.25);
    }

    @Test
    @DisplayName("conversion rate is zero, not a division error, when there are no page views yet")
    void zeroPageViewsYieldsZeroConversionRate() {
        when(repository.findLandingPageId("mani")).thenReturn(Optional.of(LANDING_PAGE_ID));
        when(repository.findExperimentStatus(LANDING_PAGE_ID)).thenReturn(Optional.empty());
        when(repository.findVariantStats(LANDING_PAGE_ID)).thenReturn(List.of(
                new RawVariantStat(VARIANT_ID.toString(), "Control", 20, true, 0, 0)
        ));

        MarketingDashboardDto dashboard = service.dashboard("mani");

        assertThat(dashboard.experimentStatus()).isEqualTo("none");
        assertThat(dashboard.variants().get(0).conversionRate()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("returns the unavailable DTO, not a thrown exception, when the marketing schema is unreachable")
    void unavailableWhenRepositoryThrows() {
        when(repository.findLandingPageId("mani")).thenThrow(new DataAccessResourceFailureException("relation \"marketing.landing_pages\" does not exist"));

        MarketingDashboardDto dashboard = service.dashboard("mani");

        assertThat(dashboard.available()).isFalse();
        assertThat(dashboard.experimentStatus()).isEqualTo("none");
        assertThat(dashboard.variants()).isEmpty();
    }

    @Test
    @DisplayName("returns the unavailable DTO when the requested slug has no landing page")
    void unavailableWhenSlugNotFound() {
        when(repository.findLandingPageId("unknown-slug")).thenReturn(Optional.empty());

        MarketingDashboardDto dashboard = service.dashboard("unknown-slug");

        assertThat(dashboard.available()).isFalse();
        assertThat(dashboard.landingPageSlug()).isEqualTo("unknown-slug");
    }
}
