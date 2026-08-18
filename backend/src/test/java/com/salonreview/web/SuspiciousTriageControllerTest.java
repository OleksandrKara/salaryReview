package com.salonreview.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salonreview.ai.SuspiciousBookingTriageService;
import com.salonreview.ai.TriageResult;
import com.salonreview.config.AiTriageProperties;
import com.salonreview.config.BusinessFeatureService;
import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.domain.TriageClassification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-edge tests for {@link SuspiciousTriageController}. Standalone {@code MockMvc} setup — the
 * role-based 403 path (PROVIDER blocked by SecurityConfig) is exercised via the same
 * {@code /api/suspicious/**} matcher that the existing clearance endpoints rely on, so it's
 * covered transitively rather than re-tested here.
 */
class SuspiciousTriageControllerTest {

    private static final Long BUSINESS_ID = 1L;

    private SuspiciousBookingTriageService service;
    private AiTriageProperties props;
    private BusinessFeatureService businessFeatures;
    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = mock(SuspiciousBookingTriageService.class);
        props = mock(AiTriageProperties.class);
        CurrentBusinessContext currentBusinessContext = mock(CurrentBusinessContext.class);
        when(currentBusinessContext.id()).thenReturn(BUSINESS_ID);
        businessFeatures = mock(BusinessFeatureService.class);
        when(businessFeatures.isEnabled(BUSINESS_ID, BusinessFeatureService.AI_TRIAGE_ENABLED)).thenReturn(true);

        SuspiciousTriageController controller =
                new SuspiciousTriageController(service, props, currentBusinessContext, businessFeatures);
        TriageExceptionHandler advice = new TriageExceptionHandler();

        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(advice)
                .build();
    }

    @Test
    @DisplayName("flag off → 404 without invoking the service")
    void flagOffReturns404() throws Exception {
        when(props.isEnabled()).thenReturn(false);

        mvc.perform(post("/api/suspicious/bk1/triage").param("year", "2026").param("month", "6"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("Phase 4.3: globally on, but off for this specific business → 404 without invoking the service")
    void businessFeatureOffReturns404() throws Exception {
        when(props.isEnabled()).thenReturn(true);
        when(businessFeatures.isEnabled(BUSINESS_ID, BusinessFeatureService.AI_TRIAGE_ENABLED)).thenReturn(false);

        mvc.perform(post("/api/suspicious/bk1/triage").param("year", "2026").param("month", "6"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("triage returns 200 with the TriageResult JSON body")
    void triageSucceeds() throws Exception {
        when(props.isEnabled()).thenReturn(true);
        TriageResult r = new TriageResult(
                TriageClassification.NEEDS_REVIEW,
                BigDecimal.valueOf(0.5),
                "explanation text",
                "draft message",
                List.of("past_appointment_no_order", "no_cash_note"),
                "v1",
                "claude-haiku-4-5");
        when(service.triage("bk1", 2026, 6)).thenReturn(Optional.of(r));

        mvc.perform(post("/api/suspicious/bk1/triage").param("year", "2026").param("month", "6"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.classification").value("NEEDS_REVIEW"))
                .andExpect(jsonPath("$.explanation").value("explanation text"))
                .andExpect(jsonPath("$.draftMessage").value("draft message"))
                .andExpect(jsonPath("$.signals[0]").value("past_appointment_no_order"))
                .andExpect(jsonPath("$.promptVersion").value("v1"))
                .andExpect(jsonPath("$.model").value("claude-haiku-4-5"));
    }

    @Test
    @DisplayName("non-flagged booking → 404")
    void nonFlaggedReturns404() throws Exception {
        when(props.isEnabled()).thenReturn(true);
        when(service.triage(anyString(), anyInt(), anyInt())).thenReturn(Optional.empty());

        mvc.perform(post("/api/suspicious/bk1/triage").param("year", "2026").param("month", "6"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("TriageFailedException → 502 with the generic user-facing message")
    void llmFailureReturns502() throws Exception {
        when(props.isEnabled()).thenReturn(true);
        when(service.triage(any(), anyInt(), anyInt())).thenThrow(
                new SuspiciousBookingTriageService.TriageFailedException("upstream boom",
                        new RuntimeException("anthropic 5xx")));

        mvc.perform(post("/api/suspicious/bk1/triage").param("year", "2026").param("month", "6"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error")
                        .value("AI explanation unavailable; please try again or review manually."));
    }

    @Test
    @DisplayName("feedback success → 200")
    void feedbackSuccess() throws Exception {
        when(props.isEnabled()).thenReturn(true);
        when(service.recordFeedback(eq("bk1"), eq(false), eq(TriageClassification.LIKELY_LEGIT)))
                .thenReturn(true);

        Map<String, Object> body = Map.of(
                "helpful", false,
                "correctedClassification", "LIKELY_LEGIT");
        mvc.perform(post("/api/suspicious/bk1/triage/feedback")
                        .contentType("application/json")
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isOk());

        verify(service).recordFeedback("bk1", false, TriageClassification.LIKELY_LEGIT);
    }

    @Test
    @DisplayName("feedback with no triage row → 404")
    void feedbackNoRow() throws Exception {
        when(props.isEnabled()).thenReturn(true);
        when(service.recordFeedback(any(), any(Boolean.class), any())).thenReturn(false);

        Map<String, Object> body = Map.of("helpful", true);
        mvc.perform(post("/api/suspicious/bk1/triage/feedback")
                        .contentType("application/json")
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isNotFound());
    }
}
