package com.salonreview.web;

import com.salonreview.ai.SeoAiAdvisorService;
import com.salonreview.config.AiSeoAdvisorProperties;
import com.salonreview.config.BusinessFeatureService;
import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.repo.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real HTTP-edge test for {@link SeoAiAdvisorController}, same standalone MockMvc + real {@link
 * TriageExceptionHandler} advice pattern as {@link FunnelAnalysisControllerTest}/{@link
 * SuspiciousTriageControllerTest}. Added after a real production bug: {@link
 * SeoAiAdvisorControllerTest}'s direct method-call tests never actually load Spring's exception
 * resolution, so a missing {@code @ExceptionHandler} mapping for {@link
 * SeoAiAdvisorService.AnalysisFailedException} went unnoticed until it leaked a raw 500 to an
 * owner clicking "Analyze SEO" in production (2026-09-03) instead of the intended 502 + friendly
 * message. This test exercises the real advice wiring so that specific class of gap can't recur
 * silently the same way {@code SeoTechnicalIssueRepositoryTest} was added in Phase 8 to close an
 * analogous mocked-repository blind spot.
 */
class SeoAiAdvisorControllerHttpTest {

    private static final Long BUSINESS_ID = 1L;

    private SeoAiAdvisorService service;
    private AiSeoAdvisorProperties props;
    private BusinessFeatureService businessFeatures;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(SeoAiAdvisorService.class);
        props = mock(AiSeoAdvisorProperties.class);
        AppUserRepository users = mock(AppUserRepository.class);
        CurrentBusinessContext currentBusinessContext = mock(CurrentBusinessContext.class);
        when(currentBusinessContext.id()).thenReturn(BUSINESS_ID);
        businessFeatures = mock(BusinessFeatureService.class);
        when(businessFeatures.isEnabled(BUSINESS_ID, BusinessFeatureService.AI_SEO_ADVISOR_ENABLED)).thenReturn(true);

        SeoAiAdvisorController controller =
                new SeoAiAdvisorController(service, props, users, currentBusinessContext, businessFeatures);
        TriageExceptionHandler advice = new TriageExceptionHandler();

        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(advice)
                .setCustomArgumentResolvers(
                        new org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @Test
    @DisplayName("SeoAiAdvisorService.AnalysisFailedException -> 502 with the generic user-facing message, not a raw 500")
    void analysisFailedReturns502() throws Exception {
        when(props.isEnabled()).thenReturn(true);
        when(service.analyze(any(), anyBoolean(), any())).thenThrow(
                new SeoAiAdvisorService.AnalysisFailedException("Claude response had no text block", null));

        mvc.perform(post("/api/owner/marketing/seo/advisor/analyze"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error")
                        .value("AI explanation unavailable; please try again or review manually."));
    }
}
