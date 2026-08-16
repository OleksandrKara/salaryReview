package com.salonreview.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.domain.TwilioSmsConfig;
import com.salonreview.sms.TwilioSmsConfigService;
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
class TwilioSmsSettingsControllerTest {

    private static final Long BUSINESS_ID = 1L;

    private TwilioSmsConfigService configService;
    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        configService = mock(TwilioSmsConfigService.class);
        CurrentBusinessContext currentBusinessContext = mock(CurrentBusinessContext.class);
        when(currentBusinessContext.id()).thenReturn(BUSINESS_ID);
        TwilioSmsSettingsController controller = new TwilioSmsSettingsController(configService, currentBusinessContext);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("GET masks the API key and secret, never the real values")
    void getMasksSecrets() throws Exception {
        when(configService.get(BUSINESS_ID)).thenReturn(TwilioSmsConfig.builder()
                .businessId(BUSINESS_ID)
                .accountSid("AC1234567890abcdef")
                .apiKey("SK1234567890abcdef")
                .apiSecret("supersecretvalue123")
                .fromPhoneNumber("+15559999999")
                .updatedAt(Instant.parse("2026-07-18T00:00:00Z"))
                .updatedBy("owner")
                .build());

        mvc.perform(get("/api/owner/settings/sms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKeyMasked").value("••••cdef"))
                .andExpect(jsonPath("$.apiSecretMasked").value("••••e123"))
                .andExpect(jsonPath("$.apiKeySet").value(true))
                .andExpect(jsonPath("$.apiSecretSet").value(true))
                .andExpect(jsonPath("$.fromPhoneNumber").value("+15559999999"))
                .andDo(result -> {
                    String body = result.getResponse().getContentAsString();
                    org.assertj.core.api.Assertions.assertThat(body)
                            .doesNotContain("SK1234567890abcdef")
                            .doesNotContain("supersecretvalue123");
                });
    }

    @Test
    @DisplayName("GET with no credentials set returns null masks and *Set:false")
    void getUnsetCredentials() throws Exception {
        when(configService.get(BUSINESS_ID)).thenReturn(TwilioSmsConfig.builder().businessId(BUSINESS_ID).build());

        mvc.perform(get("/api/owner/settings/sms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKeySet").value(false))
                .andExpect(jsonPath("$.apiSecretSet").value(false));
    }

    @Test
    @DisplayName("PUT with null fields passes null through unchanged")
    void putNullFieldsPassThrough() throws Exception {
        when(configService.update(isNull(), isNull(), isNull(), eq("+15559999999"), any(), eq(BUSINESS_ID)))
                .thenReturn(TwilioSmsConfig.builder().businessId(BUSINESS_ID).fromPhoneNumber("+15559999999").build());

        Principal owner = () -> "owner";
        mvc.perform(put("/api/owner/settings/sms")
                        .principal(owner)
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("fromPhoneNumber", "+15559999999"))))
                .andExpect(status().isOk());

        verify(configService).update(isNull(), isNull(), isNull(), eq("+15559999999"), any(), eq(BUSINESS_ID));
    }
}
