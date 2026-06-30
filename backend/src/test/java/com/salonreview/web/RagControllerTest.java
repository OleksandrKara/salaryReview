package com.salonreview.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salonreview.ai.LangSmithTracer;
import com.salonreview.config.RagProperties;
import com.salonreview.rag.RagAnswer;
import com.salonreview.rag.RagAnswerService;
import com.salonreview.rag.RagSuggestionService;
import com.salonreview.rag.StarterSuggestions;
import com.salonreview.rag.Citation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-edge tests for {@link RagController}. Standalone {@code MockMvc} — like the triage controller
 * test, role-based 403s are enforced by {@code SecurityConfig} ({@code /api/rag/admin/**}=OWNER,
 * {@code /api/rag/**}=OWNER+MANAGER) and covered transitively, not re-tested here.
 */
class RagControllerTest {

    private RagAnswerService answerService;
    private RagSuggestionService suggestionService;
    private RagProperties props;
    @SuppressWarnings("unchecked")
    private ObjectProvider<LangSmithTracer> tracerProvider = mock(ObjectProvider.class);
    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        answerService = mock(RagAnswerService.class);
        suggestionService = mock(RagSuggestionService.class);
        props = mock(RagProperties.class);
        tracerProvider = mock(ObjectProvider.class);
        RagController controller = new RagController(answerService, suggestionService, tracerProvider, props,
                mock(com.salonreview.repo.AppUserRepository.class));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(
                        new org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @Test
    @DisplayName("suggestions endpoint returns the service's topic-grouped prompts")
    void suggestions() throws Exception {
        when(suggestionService.get(any())).thenReturn(new StarterSuggestions(List.of(
                new StarterSuggestions.Topic("Policies", List.of("What's our no-show fee policy?")))));

        mvc.perform(get("/api/rag/suggestions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topics[0].label").value("Policies"))
                .andExpect(jsonPath("$.topics[0].questions[0]").value("What's our no-show fee policy?"));
    }

    @Test
    @DisplayName("flag off → 404 without invoking the service")
    void flagOffReturns404() throws Exception {
        when(props.isEnabled()).thenReturn(false);

        mvc.perform(post("/api/rag/ask").contentType("application/json")
                        .content(json.writeValueAsString(Map.of("question", "hi"))))
                .andExpect(status().isNotFound());

        verifyNoInteractions(answerService);
    }

    @Test
    @DisplayName("ask returns 200 with the cited answer body")
    void askSucceeds() throws Exception {
        when(props.isEnabled()).thenReturn(true);
        RagAnswer answer = new RagAnswer(
                "The no-show fee is $25.",
                List.of(new Citation(5L, "policies.md", "The no-show fee is $25.")),
                1, "run-abc", true);
        when(answerService.answer(eq("what's the no-show fee?"), any())).thenReturn(answer);

        mvc.perform(post("/api/rag/ask").contentType("application/json")
                        .content(json.writeValueAsString(Map.of("question", "what's the no-show fee?"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("The no-show fee is $25."))
                .andExpect(jsonPath("$.answered").value(true))
                .andExpect(jsonPath("$.citations[0].documentTitle").value("policies.md"))
                .andExpect(jsonPath("$.traceRunId").value("run-abc"));
    }

    @Test
    @DisplayName("blank question → 400")
    void blankQuestionReturns400() throws Exception {
        when(props.isEnabled()).thenReturn(true);

        mvc.perform(post("/api/rag/ask").contentType("application/json")
                        .content(json.writeValueAsString(Map.of("question", "   "))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("RagAnswerException → 502")
    void answerFailureReturns502() throws Exception {
        when(props.isEnabled()).thenReturn(true);
        when(answerService.answer(anyString(), any()))
                .thenThrow(new RagAnswerService.RagAnswerException("anthropic 5xx"));

        mvc.perform(post("/api/rag/ask").contentType("application/json")
                        .content(json.writeValueAsString(Map.of("question", "anything"))))
                .andExpect(status().isBadGateway());
    }

    @Test
    @DisplayName("feedback with a run id → 200")
    void feedbackSucceeds() throws Exception {
        when(props.isEnabled()).thenReturn(true);

        mvc.perform(post("/api/rag/ask/feedback").contentType("application/json")
                        .content(json.writeValueAsString(Map.of("runId", "run-abc", "helpful", true))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("feedback with blank run id → 400")
    void feedbackBlankRunIdReturns400() throws Exception {
        when(props.isEnabled()).thenReturn(true);

        mvc.perform(post("/api/rag/ask/feedback").contentType("application/json")
                        .content(json.writeValueAsString(Map.of("runId", "", "helpful", true))))
                .andExpect(status().isBadRequest());
    }
}
