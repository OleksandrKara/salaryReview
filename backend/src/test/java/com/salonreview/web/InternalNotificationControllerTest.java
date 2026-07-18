package com.salonreview.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salonreview.config.InternalApiProperties;
import com.salonreview.telegram.TelegramNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone {@code MockMvc} — unlike every other controller in this app, auth here is the
 * controller's own {@code X-Internal-Api-Key} check (there's no session for service-to-service
 * callers), so it's tested directly rather than left to {@code SecurityConfig}.
 */
class InternalNotificationControllerTest {

    private static final String BODY = "{\"source\":\"mani\",\"customerName\":\"Jane\",\"phoneNumber\":\"+15551234567\"," +
            "\"requestedServices\":\"manicure\",\"preferredStartAt\":\"2026-08-01T18:00:00Z\",\"note\":null}";

    private InternalApiProperties props;
    private TelegramNotificationService telegram;
    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        props = mock(InternalApiProperties.class);
        telegram = mock(TelegramNotificationService.class);
        InternalNotificationController controller = new InternalNotificationController(props, telegram);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("missing key header → 401")
    void missingKeyReturns401() throws Exception {
        when(props.getKey()).thenReturn("secret");

        mvc.perform(post("/api/internal/notifications/four-hand-request")
                        .contentType("application/json").content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("wrong key header → 401")
    void wrongKeyReturns401() throws Exception {
        when(props.getKey()).thenReturn("secret");

        mvc.perform(post("/api/internal/notifications/four-hand-request")
                        .header("X-Internal-Api-Key", "wrong")
                        .contentType("application/json").content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("blank configured key → every call 401s, even with a matching-looking header")
    void blankConfiguredKeyAlwaysRejects() throws Exception {
        when(props.getKey()).thenReturn("");

        mvc.perform(post("/api/internal/notifications/four-hand-request")
                        .header("X-Internal-Api-Key", "")
                        .contentType("application/json").content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("correct key + alert sent → 200 sent:true")
    void correctKeySentTrue() throws Exception {
        when(props.getKey()).thenReturn("secret");
        when(telegram.sendFourHandRequestAlert(any())).thenReturn(true);

        mvc.perform(post("/api/internal/notifications/four-hand-request")
                        .header("X-Internal-Api-Key", "secret")
                        .contentType("application/json").content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sent").value(true));
    }

    @Test
    @DisplayName("correct key + alert not configured → 200 sent:false, not an error")
    void correctKeySentFalse() throws Exception {
        when(props.getKey()).thenReturn("secret");
        when(telegram.sendFourHandRequestAlert(any())).thenReturn(false);

        mvc.perform(post("/api/internal/notifications/four-hand-request")
                        .header("X-Internal-Api-Key", "secret")
                        .contentType("application/json").content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sent").value(false));
    }
}
