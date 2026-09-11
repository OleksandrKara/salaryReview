package com.salonreview.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.domain.ProviderScheduleClosureAlertConfig;
import com.salonreview.sms.ProviderScheduleClosureAlertConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.security.Principal;
import java.time.Instant;
import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone {@code MockMvc}. Role gating (OWNER-only, {@code /api/owner/**}) is enforced by
 * {@code SecurityConfig} and covered transitively, not re-tested here — see {@code
 * TelegramSettingsControllerTest}'s own doc.
 */
class ProviderScheduleClosureAlertSettingsControllerTest {

    private static final Long BUSINESS_ID = 1L;

    private ProviderScheduleClosureAlertConfigService configService;
    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    /** Mirrors the real {@code GlobalExceptionHandler}'s {@code IllegalArgumentException} → 400
     * mapping, since this standalone MockMvc setup has no Spring context to pull it from. */
    @RestControllerAdvice
    static class TestExceptionHandler {
        @ExceptionHandler(IllegalArgumentException.class)
        @ResponseStatus(HttpStatus.BAD_REQUEST)
        public String handle(IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    @BeforeEach
    void setUp() {
        configService = mock(ProviderScheduleClosureAlertConfigService.class);
        CurrentBusinessContext currentBusinessContext = mock(CurrentBusinessContext.class);
        when(currentBusinessContext.id()).thenReturn(BUSINESS_ID);
        ProviderScheduleClosureAlertSettingsController controller =
                new ProviderScheduleClosureAlertSettingsController(configService, currentBusinessContext);
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new TestExceptionHandler()).build();
    }

    @Test
    @DisplayName("GET returns the business's configured threshold")
    void getReturnsConfiguredThreshold() throws Exception {
        when(configService.getSettings(BUSINESS_ID)).thenReturn(
                new ProviderScheduleClosureAlertConfigService.Settings(12, Instant.parse("2026-09-10T18:00:00Z"), "owner@test"));

        mvc.perform(get("/api/owner/settings/provider-schedule-closure-alert"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.noticeThresholdHours").value(12))
                .andExpect(jsonPath("$.updatedBy").value("owner@test"));
    }

    @Test
    @DisplayName("GET with no row yet returns the 24h default and null updatedAt/updatedBy")
    void getReturnsDefaultWhenUnconfigured() throws Exception {
        when(configService.getSettings(BUSINESS_ID)).thenReturn(
                new ProviderScheduleClosureAlertConfigService.Settings(24, null, null));

        mvc.perform(get("/api/owner/settings/provider-schedule-closure-alert"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.noticeThresholdHours").value(24))
                .andExpect(jsonPath("$.updatedAt").doesNotExist())
                .andExpect(jsonPath("$.updatedBy").doesNotExist());
    }

    @Test
    @DisplayName("PUT saves the new threshold, scoped to the current business, under the caller's name")
    void putSavesThreshold() throws Exception {
        when(configService.update(eq(BUSINESS_ID), eq(6), eq("owner"))).thenReturn(
                ProviderScheduleClosureAlertConfig.builder().businessId(BUSINESS_ID).noticeThresholdHours(6)
                        .updatedAt(Instant.parse("2026-09-10T18:00:00Z")).updatedBy("owner").build());

        Principal owner = () -> "owner";
        mvc.perform(put("/api/owner/settings/provider-schedule-closure-alert")
                        .principal(owner)
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("noticeThresholdHours", 6))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.noticeThresholdHours").value(6));

        verify(configService).update(BUSINESS_ID, 6, "owner");
    }

    @Test
    @DisplayName("PUT with an invalid value surfaces the service's validation error as a 400")
    void putRejectsInvalidValue() throws Exception {
        when(configService.update(eq(BUSINESS_ID), eq(0), eq("owner")))
                .thenThrow(new IllegalArgumentException("Notice threshold must be between 1 and 168 hours"));

        Principal owner = () -> "owner";
        mvc.perform(put("/api/owner/settings/provider-schedule-closure-alert")
                        .principal(owner)
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("noticeThresholdHours", 0))))
                .andExpect(status().isBadRequest());
    }
}
