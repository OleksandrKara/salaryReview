package com.salonreview.web;

import com.salonreview.config.BusinessFeatureService;
import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.seo.SeoCompetitorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SeoCompetitorControllerTest {

    private SeoCompetitorService service;
    private BusinessFeatureService businessFeatures;
    private CurrentBusinessContext currentBusinessContext;
    private SeoCompetitorController controller;

    @BeforeEach
    void setUp() {
        service = mock(SeoCompetitorService.class);
        businessFeatures = mock(BusinessFeatureService.class);
        currentBusinessContext = new CurrentBusinessContext();
        controller = new SeoCompetitorController(service, businessFeatures, currentBusinessContext);
        when(service.competitors(any())).thenReturn(List.of());
    }

    @Test
    @DisplayName("list() 404s when seo-monitoring.enabled is off for the business")
    void listReturns404WhenFeatureDisabled() {
        currentBusinessContext.runAs(1L, () -> {
            when(businessFeatures.isEnabled(1L, BusinessFeatureService.SEO_MONITORING_ENABLED)).thenReturn(false);

            assertThatThrownBy(() -> controller.list())
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("404");
        });
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("add() rejects a blank name or website without calling the service")
    void addRejectsBlank() {
        currentBusinessContext.runAs(1L, () -> {
            when(businessFeatures.isEnabled(1L, BusinessFeatureService.SEO_MONITORING_ENABLED)).thenReturn(true);

            assertThatThrownBy(() -> controller.add(new SeoCompetitorController.CompetitorRequest("  ", "https://x.example", null, null)))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("400");
            verify(service, never()).addCompetitor(any(), any(), any(), any(), any());
        });
    }

    @Test
    @DisplayName("add() trims fields and forwards to the service")
    void addTrimsAndForwards() {
        currentBusinessContext.runAs(1L, () -> {
            when(businessFeatures.isEnabled(1L, BusinessFeatureService.SEO_MONITORING_ENABLED)).thenReturn(true);

            controller.add(new SeoCompetitorController.CompetitorRequest("  Competitor A  ", "  https://a.example  ", "  Downtown  ", null));

            verify(service).addCompetitor(1L, "Competitor A", "https://a.example", "Downtown", null);
        });
    }

    @Test
    @DisplayName("update() forwards GBP fields and the active flag when present")
    void updateForwardsFields() {
        currentBusinessContext.runAs(1L, () -> {
            when(businessFeatures.isEnabled(1L, BusinessFeatureService.SEO_MONITORING_ENABLED)).thenReturn(true);

            controller.update(5L, new SeoCompetitorController.CompetitorUpdateRequest(
                    java.math.BigDecimal.valueOf(4.5), 100, false));

            verify(service).updateCompetitorGbp(1L, 5L, java.math.BigDecimal.valueOf(4.5), 100);
            verify(service).setCompetitorActive(1L, 5L, false);
        });
    }

    @Test
    @DisplayName("update() does not toggle active when not provided in the request")
    void updateSkipsActiveWhenNull() {
        currentBusinessContext.runAs(1L, () -> {
            when(businessFeatures.isEnabled(1L, BusinessFeatureService.SEO_MONITORING_ENABLED)).thenReturn(true);

            controller.update(5L, new SeoCompetitorController.CompetitorUpdateRequest(null, null, null));

            verify(service, never()).setCompetitorActive(any(), any(), anyBoolean());
        });
    }

    @Test
    @DisplayName("remove() forwards the id to the service")
    void removeForwardsId() {
        currentBusinessContext.runAs(1L, () -> {
            when(businessFeatures.isEnabled(1L, BusinessFeatureService.SEO_MONITORING_ENABLED)).thenReturn(true);

            controller.remove(5L);

            verify(service).removeCompetitor(1L, 5L);
        });
    }

    @Test
    @DisplayName("list() returns the service's rows mapped to DTOs when enabled")
    void listReturnsMappedRows() {
        currentBusinessContext.runAs(1L, () -> {
            when(businessFeatures.isEnabled(1L, BusinessFeatureService.SEO_MONITORING_ENABLED)).thenReturn(true);
            when(service.competitors(1L)).thenReturn(List.of(new SeoCompetitorService.CompetitorRow(
                    1L, "Competitor A", "https://a.example", "Downtown", null, true,
                    java.math.BigDecimal.valueOf(4.5), 100, java.time.Instant.now(), null, null)));

            List<SeoCompetitorController.CompetitorRowDto> result = controller.list();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).name()).isEqualTo("Competitor A");
        });
    }
}
