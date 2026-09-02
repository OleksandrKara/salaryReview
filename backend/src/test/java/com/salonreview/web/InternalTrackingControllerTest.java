package com.salonreview.web;

import com.salonreview.config.InternalApiProperties;
import com.salonreview.tracking.TrackingConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Standalone {@code MockMvc} — same reasoning as {@code InternalBusinessControllerTest}: auth
 * here is the controller's own {@code X-Internal-Api-Key} check, not a session. */
class InternalTrackingControllerTest {

    private InternalApiProperties props;
    private TrackingConfigService trackingConfig;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        props = mock(InternalApiProperties.class);
        trackingConfig = mock(TrackingConfigService.class);
        InternalTrackingController controller = new InternalTrackingController(props, trackingConfig);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("missing key header → 401")
    void missingKeyReturns401() throws Exception {
        when(props.getKey()).thenReturn("secret");

        mvc.perform(get("/api/internal/tracking-config").param("domain", "akluxnails.com"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("wrong key header → 401")
    void wrongKeyReturns401() throws Exception {
        when(props.getKey()).thenReturn("secret");

        mvc.perform(get("/api/internal/tracking-config")
                        .header("X-Internal-Api-Key", "wrong")
                        .param("domain", "akluxnails.com"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("configured hostname — 200 with the real id")
    void configuredHostnameReturnsId() throws Exception {
        when(props.getKey()).thenReturn("secret");
        when(trackingConfig.clarityProjectIdFor("akluxnails.com")).thenReturn("abc123");

        mvc.perform(get("/api/internal/tracking-config")
                        .header("X-Internal-Api-Key", "secret")
                        .param("domain", "akluxnails.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clarityProjectId").value("abc123"));
    }

    @Test
    @DisplayName("unconfigured/unknown hostname — still 200, with a null id, not a 404 — a caller "
            + "shouldn't have to distinguish \"no row yet\" from \"row with no id set\"")
    void unconfiguredHostnameReturns200WithNullId() throws Exception {
        when(props.getKey()).thenReturn("secret");
        when(trackingConfig.clarityProjectIdFor("unknown.example.com")).thenReturn(null);

        mvc.perform(get("/api/internal/tracking-config")
                        .header("X-Internal-Api-Key", "secret")
                        .param("domain", "unknown.example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clarityProjectId").value(org.hamcrest.Matchers.nullValue()));
    }
}
