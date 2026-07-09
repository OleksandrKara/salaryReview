package com.salonreview.web;

import com.salonreview.ai.FunnelAnalysisResult;
import com.salonreview.ai.FunnelAnalysisResult.PrioritizedRecommendation;
import com.salonreview.ai.FunnelAnalysisService;
import com.salonreview.config.AiFunnelAnalysisProperties;
import com.salonreview.domain.ImpactLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-edge tests for {@link FunnelAnalysisController} — same standalone MockMvc pattern as
 * {@link SuspiciousTriageControllerTest}. The service's actual Claude-calling logic is exercised
 * indirectly by mirroring {@link com.salonreview.ai.SuspiciousBookingTriageService}'s already-
 * proven-in-production shape, not re-verified here.
 */
class FunnelAnalysisControllerTest {

    private FunnelAnalysisService service;
    private AiFunnelAnalysisProperties props;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(FunnelAnalysisService.class);
        props = mock(AiFunnelAnalysisProperties.class);

        FunnelAnalysisController controller = new FunnelAnalysisController(service, props);
        TriageExceptionHandler advice = new TriageExceptionHandler();

        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(advice)
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
                "claude-sonnet-5");
        when(service.analyze("home", "homepage_booking_v1", true)).thenReturn(Optional.of(r));

        mvc.perform(post("/api/owner/marketing/funnel/analyze")
                        .param("slug", "home")
                        .param("flowKey", "homepage_booking_v1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.biggestBottleneckStep").value("addons"))
                .andExpect(jsonPath("$.recommendations[0].title").value("Default the add-ons step to \"no add-on\""))
                .andExpect(jsonPath("$.recommendations[0].expectedImpact").value("HIGH"))
                .andExpect(jsonPath("$.promptVersion").value("v1"))
                .andExpect(jsonPath("$.model").value("claude-sonnet-5"));
    }

    @Test
    @DisplayName("no funnel data for slug/flowKey → 404")
    void noFunnelDataReturns404() throws Exception {
        when(props.isEnabled()).thenReturn(true);
        when(service.analyze("home", "unknown_flow", true)).thenReturn(Optional.empty());

        mvc.perform(post("/api/owner/marketing/funnel/analyze")
                        .param("slug", "home")
                        .param("flowKey", "unknown_flow"))
                .andExpect(status().isNotFound());
    }
}
