package com.salonreview.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.domain.TelegramNotificationConfig;
import com.salonreview.telegram.TelegramConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.security.Principal;
import java.time.Instant;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone {@code MockMvc}. Role gating (OWNER-only, {@code /api/owner/**}) is enforced by
 * {@code SecurityConfig} and covered transitively, not re-tested here — see {@code RagControllerTest}.
 */
class TelegramSettingsControllerTest {

    private static final Long BUSINESS_ID = 1L;

    private TelegramConfigService configService;
    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        configService = mock(TelegramConfigService.class);
        CurrentBusinessContext currentBusinessContext = mock(CurrentBusinessContext.class);
        when(currentBusinessContext.id()).thenReturn(BUSINESS_ID);
        TelegramSettingsController controller = new TelegramSettingsController(configService, currentBusinessContext);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("GET returns a masked token, never the real one")
    void getMasksToken() throws Exception {
        when(configService.get(BUSINESS_ID)).thenReturn(TelegramNotificationConfig.builder()
                .businessId(BUSINESS_ID)
                .botToken("123456789:FAKE-TEST-TOKEN-NOT-REAL-abcd")
                .chatId("999")
                .updatedAt(Instant.parse("2026-07-18T00:00:00Z"))
                .updatedBy("owner")
                .build());

        mvc.perform(get("/api/owner/settings/telegram"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.botTokenMasked").value("••••abcd"))
                .andExpect(jsonPath("$.botTokenSet").value(true))
                .andExpect(jsonPath("$.chatId").value("999"))
                .andDo(result -> {
                    String body = result.getResponse().getContentAsString();
                    org.assertj.core.api.Assertions.assertThat(body)
                            .doesNotContain("123456789:FAKE-TEST-TOKEN-NOT-REAL-abcd");
                });
    }

    @Test
    @DisplayName("GET with no token set returns null mask and botTokenSet:false")
    void getUnsetToken() throws Exception {
        when(configService.get(BUSINESS_ID)).thenReturn(
                TelegramNotificationConfig.builder().businessId(BUSINESS_ID).build());

        mvc.perform(get("/api/owner/settings/telegram"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.botTokenMasked").doesNotExist())
                .andExpect(jsonPath("$.botTokenSet").value(false));
    }

    @Test
    @DisplayName("PUT with null botToken passes null through unchanged (service owns the semantics)")
    void putNullBotTokenPassesThrough() throws Exception {
        when(configService.update(isNull(), eq("999888777"), any(), eq(BUSINESS_ID)))
                .thenReturn(TelegramNotificationConfig.builder().businessId(BUSINESS_ID).chatId("999888777").build());

        Principal owner = () -> "owner";
        mvc.perform(put("/api/owner/settings/telegram")
                        .principal(owner)
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("chatId", "999888777"))))
                .andExpect(status().isOk());

        verify(configService).update(isNull(), eq("999888777"), any(), eq(BUSINESS_ID));
    }
}
