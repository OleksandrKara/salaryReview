package com.salonreview.web;

import com.salonreview.ai.FunnelAnalysisResult;
import com.salonreview.ai.FunnelAnalysisResult.PrioritizedRecommendation;
import com.salonreview.ai.FunnelAnalysisService;
import com.salonreview.config.AiFunnelAnalysisProperties;
import com.salonreview.config.BusinessFeatureService;
import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.domain.ImpactLevel;
import com.salonreview.domain.Language;
import com.salonreview.repo.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-edge tests for {@link FunnelAnalysisController} — same standalone MockMvc pattern as
 * {@link SuspiciousTriageControllerTest}. The service's actual Claude-calling logic is exercised
 * indirectly by mirroring {@link com.salonreview.ai.SuspiciousBookingTriageService}'s already-
 * proven-in-production shape, not re-verified here. No {@code @AuthenticationPrincipal} is wired
 * in this standalone MockMvc setup, so {@code me} resolves to null in every request here — the
 * controller's {@code language(me)} helper falls back to {@link Language#EN} in that case, hence
 * every stub below expects {@code Language.EN}.
 */
class FunnelAnalysisControllerTest {

    private static final Long BUSINESS_ID = 1L;

    private FunnelAnalysisService service;
    private AiFunnelAnalysisProperties props;
    private AppUserRepository users;
    private BusinessFeatureService businessFeatures;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(FunnelAnalysisService.class);
        props = mock(AiFunnelAnalysisProperties.class);
        users = mock(AppUserRepository.class);
        CurrentBusinessContext currentBusinessContext = mock(CurrentBusinessContext.class);
        when(currentBusinessContext.id()).thenReturn(BUSINESS_ID);
        businessFeatures = mock(BusinessFeatureService.class);
        when(businessFeatures.isEnabled(BUSINESS_ID, BusinessFeatureService.AI_FUNNEL_ANALYSIS_ENABLED)).thenReturn(true);

        FunnelAnalysisController controller =
                new FunnelAnalysisController(service, props, users, currentBusinessContext, businessFeatures);
        TriageExceptionHandler advice = new TriageExceptionHandler();

        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(advice)
                .setCustomArgumentResolvers(
                        new org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @Test
    @DisplayName("flag off → 404 without invoking the service")
    void flagOffReturns404() throws Exception {
        when(props.isEnabled()).thenReturn(false);

        mvc.perform(post("/api/owner/marketing/funnel/analyze")
                        .param("slug", "home")
                        .param("flowKey", "homepage_booking_v1"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("Phase 4.3: globally on, but off for this specific business → 404 without invoking the service")
    void businessFeatureOffReturns404() throws Exception {
        when(props.isEnabled()).thenReturn(true);
        when(businessFeatures.isEnabled(BUSINESS_ID, BusinessFeatureService.AI_FUNNEL_ANALYSIS_ENABLED)).thenReturn(false);

        mvc.perform(post("/api/owner/marketing/funnel/analyze")
                        .param("slug", "home")
                        .param("flowKey", "homepage_booking_v1"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("analyze returns 200 with the FunnelAnalysisResult JSON body")
    void analyzeSucceeds() throws Exception {
        when(props.isEnabled()).thenReturn(true);
        FunnelAnalysisResult r = new FunnelAnalysisResult(
                "addons",
                "Half of everyone who reaches add-ons never continues to date/time.",
                List.of(new PrioritizedRecommendation(
                        "Default the add-ons step to \"no add-on\"",
                        "The add-ons step has the highest drop-off of any step in this funnel.",
                        ImpactLevel.HIGH)),
                List.of(),
                List.of("Move contact info collection to the end of the flow"),
                "Default add-ons to skip and require an explicit opt-in instead.",
                "v1",
                "claude-sonnet-5",
                Instant.parse("2026-07-11T00:00:00Z"));
        when(service.analyze("home", "homepage_booking_v1", true, false, Language.EN)).thenReturn(Optional.of(r));

        mvc.perform(post("/api/owner/marketing/funnel/analyze")
                        .param("slug", "home")
                        .param("flowKey", "homepage_booking_v1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.biggestBottleneckStep").value("addons"))
                .andExpect(jsonPath("$.recommendations[0].title").value("Default the add-ons step to \"no add-on\""))
                .andExpect(jsonPath("$.recommendations[0].expectedImpact").value("HIGH"))
                .andExpect(jsonPath("$.promptVersion").value("v1"))
                .andExpect(jsonPath("$.model").value("claude-sonnet-5"))
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    @DisplayName("force=true passes through to the service, bypassing the cache")
    void forceParamPassesThrough() throws Exception {
        when(props.isEnabled()).thenReturn(true);
        FunnelAnalysisResult r = new FunnelAnalysisResult(
                "addons", "Explanation.", List.of(), List.of(), List.of(), "Do this first.",
                "v1", "claude-sonnet-5", Instant.now());
        when(service.analyze("home", "homepage_booking_v1", true, true, Language.EN)).thenReturn(Optional.of(r));

        mvc.perform(post("/api/owner/marketing/funnel/analyze")
                        .param("slug", "home")
                        .param("flowKey", "homepage_booking_v1")
                        .param("force", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.biggestBottleneckStep").value("addons"));
    }

    @Test
    @DisplayName("no funnel data for slug/flowKey → 404")
    void noFunnelDataReturns404() throws Exception {
        when(props.isEnabled()).thenReturn(true);
        when(service.analyze("home", "unknown_flow", true, false, Language.EN)).thenReturn(Optional.empty());

        mvc.perform(post("/api/owner/marketing/funnel/analyze")
                        .param("slug", "home")
                        .param("flowKey", "unknown_flow"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("history: flag off → 404 without invoking the service")
    void historyFlagOffReturns404() throws Exception {
        when(props.isEnabled()).thenReturn(false);

        mvc.perform(get("/api/owner/marketing/funnel/analyze/history")
                        .param("slug", "home")
                        .param("flowKey", "homepage_booking_v1"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("history returns 200 with a list of past analyses, newest first")
    void historySucceeds() throws Exception {
        when(props.isEnabled()).thenReturn(true);
        FunnelAnalysisResult newer = new FunnelAnalysisResult(
                "addons", "Explanation A.", List.of(), List.of(), List.of(), "Do A.",
                "v1", "claude-sonnet-5", Instant.parse("2026-07-11T00:00:00Z"));
        FunnelAnalysisResult older = new FunnelAnalysisResult(
                "contact", "Explanation B.", List.of(), List.of(), List.of(), "Do B.",
                "v1", "claude-sonnet-5", Instant.parse("2026-07-10T00:00:00Z"));
        when(service.history("home", "homepage_booking_v1")).thenReturn(List.of(newer, older));

        mvc.perform(get("/api/owner/marketing/funnel/analyze/history")
                        .param("slug", "home")
                        .param("flowKey", "homepage_booking_v1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].biggestBottleneckStep").value("addons"))
                .andExpect(jsonPath("$[1].biggestBottleneckStep").value("contact"));
    }
}
