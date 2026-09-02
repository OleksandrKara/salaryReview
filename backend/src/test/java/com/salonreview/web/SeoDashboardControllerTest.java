package com.salonreview.web;

import com.salonreview.config.BusinessFeatureService;
import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.seo.SeoDashboardService;
import com.salonreview.seo.SeoSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SeoDashboardControllerTest {

    private SeoDashboardService dashboardService;
    private SeoSyncService syncService;
    private BusinessFeatureService businessFeatures;
    private CurrentBusinessContext currentBusinessContext;
    private SeoDashboardController controller;

    @BeforeEach
    void setUp() {
        dashboardService = mock(SeoDashboardService.class);
        syncService = mock(SeoSyncService.class);
        businessFeatures = mock(BusinessFeatureService.class);
        currentBusinessContext = new CurrentBusinessContext();
        controller = new SeoDashboardController(dashboardService, syncService, businessFeatures, currentBusinessContext);
    }

    @Test
    @DisplayName("overview() 404s when seo-monitoring.enabled is off for the business")
    void overviewReturns404WhenFeatureDisabled() {
        currentBusinessContext.runAs(1L, () -> {
            when(businessFeatures.isEnabled(1L, BusinessFeatureService.SEO_MONITORING_ENABLED)).thenReturn(false);

            assertThatThrownBy(() -> controller.overview(28))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("404");
        });
        verifyNoInteractions(dashboardService);
    }

    @Test
    @DisplayName("overview() returns the dashboard's overview when the feature is enabled")
    void overviewReturnsDataWhenFeatureEnabled() {
        currentBusinessContext.runAs(1L, () -> {
            when(businessFeatures.isEnabled(1L, BusinessFeatureService.SEO_MONITORING_ENABLED)).thenReturn(true);
            when(dashboardService.overview(1L, 28)).thenReturn(new SeoDashboardService.Overview(
                    true, null, null, List.of(), List.of(), List.of(), List.of(), null, null, List.of(),
                    null, null, null, List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), List.of(), List.of()));

            SeoDashboardController.SeoOverviewDto dto = controller.overview(28);

            assertThat(dto.connected()).isTrue();
        });
    }

    @Test
    @DisplayName("sync() 404s when disabled and never calls the sync service")
    void syncReturns404WhenFeatureDisabled() {
        currentBusinessContext.runAs(1L, () -> {
            when(businessFeatures.isEnabled(1L, BusinessFeatureService.SEO_MONITORING_ENABLED)).thenReturn(false);

            assertThatThrownBy(() -> controller.sync()).isInstanceOf(ResponseStatusException.class);
        });
        verifyNoInteractions(syncService);
    }

    @Test
    @DisplayName("sync() triggers all three sync methods for the calling business when enabled")
    void syncTriggersAllSyncMethodsWhenEnabled() {
        currentBusinessContext.runAs(1L, () -> {
            when(businessFeatures.isEnabled(1L, BusinessFeatureService.SEO_MONITORING_ENABLED)).thenReturn(true);
            when(dashboardService.overview(eq(1L), anyInt())).thenReturn(new SeoDashboardService.Overview(
                    true, null, null, List.of(), List.of(), List.of(), List.of(), null, null, List.of(),
                    null, null, null, List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), List.of(), List.of()));

            controller.sync();

            verify(syncService).syncSearchConsole(1L);
            verify(syncService).syncAnalytics(1L);
            verify(syncService).syncPageSpeed(1L);
        });
    }

    @Test
    @DisplayName("addTrackedQuery() rejects a blank query without calling the service")
    void addTrackedQueryRejectsBlank() {
        currentBusinessContext.runAs(1L, () -> {
            when(businessFeatures.isEnabled(1L, BusinessFeatureService.SEO_MONITORING_ENABLED)).thenReturn(true);

            assertThatThrownBy(() -> controller.addTrackedQuery(new SeoDashboardController.TrackedQueryRequest("   ")))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("400");
            verify(dashboardService, never()).addTrackedQuery(any(), any());
        });
    }

    @Test
    @DisplayName("addTrackedQuery() trims and forwards a real query, then returns the refreshed overview")
    void addTrackedQueryForwardsTrimmedQuery() {
        currentBusinessContext.runAs(1L, () -> {
            when(businessFeatures.isEnabled(1L, BusinessFeatureService.SEO_MONITORING_ENABLED)).thenReturn(true);
            when(dashboardService.overview(eq(1L), anyInt())).thenReturn(new SeoDashboardService.Overview(
                    true, null, null, List.of(), List.of(), List.of(), List.of(), null, null, List.of(),
                    null, null, null, List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), List.of(), List.of()));

            controller.addTrackedQuery(new SeoDashboardController.TrackedQueryRequest("  russian manicure san diego  "));

            verify(dashboardService).addTrackedQuery(1L, "russian manicure san diego");
        });
    }

    @Test
    @DisplayName("removeTrackedQuery() forwards to the service and returns the refreshed overview")
    void removeTrackedQueryForwardsQuery() {
        currentBusinessContext.runAs(1L, () -> {
            when(businessFeatures.isEnabled(1L, BusinessFeatureService.SEO_MONITORING_ENABLED)).thenReturn(true);
            when(dashboardService.overview(eq(1L), anyInt())).thenReturn(new SeoDashboardService.Overview(
                    true, null, null, List.of(), List.of(), List.of(), List.of(), null, null, List.of(),
                    null, null, null, List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), List.of(), List.of()));

            controller.removeTrackedQuery("russian manicure san diego");

            verify(dashboardService).removeTrackedQuery(1L, "russian manicure san diego");
        });
    }
}
