package com.salonreview.web;

import com.salonreview.config.InternalApiProperties;
import com.salonreview.square.SquareMonthAggregatorShadowDiffService;
import com.salonreview.square.SquareMonthAggregatorShadowDiffService.ShadowDiffResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Standalone {@code MockMvc} — same reasoning as {@link InternalBusinessControllerTest}. */
class InternalSquareShadowDiffControllerTest {

    private InternalApiProperties props;
    private SquareMonthAggregatorShadowDiffService shadowDiff;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        props = mock(InternalApiProperties.class);
        shadowDiff = mock(SquareMonthAggregatorShadowDiffService.class);
        InternalSquareShadowDiffController controller =
                new InternalSquareShadowDiffController(props, shadowDiff);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("month: missing key header → 401")
    void monthMissingKeyReturns401() throws Exception {
        when(props.getKey()).thenReturn("secret");

        mvc.perform(get("/api/internal/square-shadow-diff")
                        .param("businessId", "1").param("year", "2026").param("month", "5"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("month: wrong key header → 401")
    void monthWrongKeyReturns401() throws Exception {
        when(props.getKey()).thenReturn("secret");

        mvc.perform(get("/api/internal/square-shadow-diff")
                        .header("X-Internal-Api-Key", "wrong")
                        .param("businessId", "1").param("year", "2026").param("month", "5"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("month: valid key returns the shadow-diff result")
    void monthValidKeyReturnsResult() throws Exception {
        when(props.getKey()).thenReturn("secret");
        when(shadowDiff.diff(1L, 2026, 5, new BigDecimal("60"))).thenReturn(
                new ShadowDiffResult(1L, 2026, 5, true, List.of()));

        mvc.perform(get("/api/internal/square-shadow-diff")
                        .header("X-Internal-Api-Key", "secret")
                        .param("businessId", "1").param("year", "2026").param("month", "5")
                        .param("cutoff", "60"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clean").value(true))
                .andExpect(jsonPath("$.discrepancies").isEmpty());
    }

    @Test
    @DisplayName("range: missing key header → 401, never calls the diff service")
    void rangeMissingKeyReturns401() throws Exception {
        when(props.getKey()).thenReturn("secret");

        mvc.perform(get("/api/internal/square-shadow-diff/range").param("businessId", "1"))
                .andExpect(status().isUnauthorized());
        verify(shadowDiff, times(0)).diff(any(), anyInt(), anyInt(), any());
    }

    @Test
    @DisplayName("range: aggregates clean/dirty counts across every checked month")
    void rangeSummarizesCleanAndDirtyMonths() throws Exception {
        when(props.getKey()).thenReturn("secret");
        when(shadowDiff.diff(any(), anyInt(), anyInt(), any()))
                .thenReturn(new ShadowDiffResult(1L, 2026, 1, true, List.of()))
                .thenReturn(new ShadowDiffResult(1L, 2026, 2, false, List.of("provider TM1 first half differs")))
                .thenReturn(new ShadowDiffResult(1L, 2026, 3, true, List.of()));

        mvc.perform(get("/api/internal/square-shadow-diff/range")
                        .header("X-Internal-Api-Key", "secret")
                        .param("businessId", "1").param("months", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthsChecked").value(3))
                .andExpect(jsonPath("$.cleanMonths").value(2))
                .andExpect(jsonPath("$.dirtyMonths").value(1));
        verify(shadowDiff, times(3)).diff(any(), anyInt(), anyInt(), any());
    }
}
