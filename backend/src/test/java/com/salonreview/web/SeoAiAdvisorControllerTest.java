package com.salonreview.web;

import com.salonreview.ai.SeoAiAdvisorService;
import com.salonreview.ai.SeoAnalysisResult;
import com.salonreview.config.AiSeoAdvisorProperties;
import com.salonreview.config.AppUserPrincipal;
import com.salonreview.config.BusinessFeatureService;
import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.domain.AppUser;
import com.salonreview.domain.Language;
import com.salonreview.repo.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Direct method-call tests, same style as {@link SeoDashboardControllerTest} — no MockMvc/HTTP
 * layer needed since there's no path/query-param parsing to exercise here. */
class SeoAiAdvisorControllerTest {

    private static final Long BUSINESS_ID = 1L;

    private SeoAiAdvisorService service;
    private AiSeoAdvisorProperties props;
    private AppUserRepository users;
    private CurrentBusinessContext currentBusinessContext;
    private BusinessFeatureService businessFeatures;
    private SeoAiAdvisorController controller;

    @BeforeEach
    void setUp() {
        service = mock(SeoAiAdvisorService.class);
        props = mock(AiSeoAdvisorProperties.class);
        users = mock(AppUserRepository.class);
        currentBusinessContext = mock(CurrentBusinessContext.class);
        when(currentBusinessContext.id()).thenReturn(BUSINESS_ID);
        businessFeatures = mock(BusinessFeatureService.class);
        controller = new SeoAiAdvisorController(service, props, users, currentBusinessContext, businessFeatures);
    }

    private static SeoAnalysisResult canned() {
        return new SeoAnalysisResult(SeoAnalysisResult.OverallStatus.HEALTHY, "Summary.",
                List.of(), List.of(), List.of(), "v1", "claude-sonnet-5", null);
    }

    @Test
    @DisplayName("analyze() 404s when the deployment-level flag is off, without calling the service")
    void analyzeReturns404WhenDeploymentFlagOff() {
        when(props.isEnabled()).thenReturn(false);

        ResponseEntity<SeoAnalysisResult> result = controller.analyze(false, null);

        assertThat(result.getStatusCode().value()).isEqualTo(404);
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("analyze() 404s when the business_feature gate is off, without calling the service")
    void analyzeReturns404WhenBusinessFeatureOff() {
        when(props.isEnabled()).thenReturn(true);
        when(businessFeatures.isEnabled(BUSINESS_ID, BusinessFeatureService.AI_SEO_ADVISOR_ENABLED)).thenReturn(false);

        ResponseEntity<SeoAnalysisResult> result = controller.analyze(false, null);

        assertThat(result.getStatusCode().value()).isEqualTo(404);
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("analyze() resolves the caller's language and forwards force, then returns the result")
    void analyzeForwardsForceAndLanguage() {
        when(props.isEnabled()).thenReturn(true);
        when(businessFeatures.isEnabled(BUSINESS_ID, BusinessFeatureService.AI_SEO_ADVISOR_ENABLED)).thenReturn(true);
        AppUserPrincipal me = mock(AppUserPrincipal.class);
        when(me.getUserId()).thenReturn(7L);
        AppUser user = AppUser.builder().id(7L).preferredLanguage(Language.RU).build();
        when(users.findById(7L)).thenReturn(Optional.of(user));
        when(service.analyze(BUSINESS_ID, true, Language.RU)).thenReturn(Optional.of(canned()));

        ResponseEntity<SeoAnalysisResult> result = controller.analyze(true, me);

        assertThat(result.getStatusCode().value()).isEqualTo(200);
        verify(service).analyze(BUSINESS_ID, true, Language.RU);
    }

    @Test
    @DisplayName("analyze() 404s when the service has nothing to analyze (no seo_connection)")
    void analyzeReturns404WhenServiceReturnsEmpty() {
        when(props.isEnabled()).thenReturn(true);
        when(businessFeatures.isEnabled(BUSINESS_ID, BusinessFeatureService.AI_SEO_ADVISOR_ENABLED)).thenReturn(true);
        when(service.analyze(eq(BUSINESS_ID), any(Boolean.class), any())).thenReturn(Optional.empty());

        ResponseEntity<SeoAnalysisResult> result = controller.analyze(false, null);

        assertThat(result.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("history() 404s when disabled, without calling the service")
    void historyReturns404WhenDisabled() {
        when(props.isEnabled()).thenReturn(false);

        ResponseEntity<List<SeoAnalysisResult>> result = controller.history();

        assertThat(result.getStatusCode().value()).isEqualTo(404);
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("history() returns the service's list when enabled")
    void historyReturnsServiceList() {
        when(props.isEnabled()).thenReturn(true);
        when(businessFeatures.isEnabled(BUSINESS_ID, BusinessFeatureService.AI_SEO_ADVISOR_ENABLED)).thenReturn(true);
        when(service.history(BUSINESS_ID)).thenReturn(List.of(canned()));

        ResponseEntity<List<SeoAnalysisResult>> result = controller.history();

        assertThat(result.getBody()).hasSize(1);
    }
}
