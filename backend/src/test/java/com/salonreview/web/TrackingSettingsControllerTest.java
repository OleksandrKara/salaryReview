package com.salonreview.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.tracking.TrackingConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Standalone {@code MockMvc}. Role gating is enforced by {@code SecurityConfig} and covered
 * transitively, not re-tested here — see {@code TwilioSmsSettingsControllerTest}'s own doc. */
class TrackingSettingsControllerTest {

    private static final Long BUSINESS_ID = 1L;

    private TrackingConfigService trackingConfig;
    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        trackingConfig = mock(TrackingConfigService.class);
        CurrentBusinessContext currentBusinessContext = mock(CurrentBusinessContext.class);
        when(currentBusinessContext.id()).thenReturn(BUSINESS_ID);
        TrackingSettingsController controller = new TrackingSettingsController(trackingConfig, currentBusinessContext);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("GET lists this business's sites, scoped via CurrentBusinessContext")
    void getListsSitesForCurrentBusiness() throws Exception {
        when(trackingConfig.list(BUSINESS_ID)).thenReturn(List.of(
                new TrackingConfigService.Site("akluxnails.com", "AK.LUX.NAILS — marketing site", "abc123",
                        Instant.parse("2026-09-01T00:00:00Z"), "owner")));

        mvc.perform(get("/api/owner/settings/tracking"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].hostname").value("akluxnails.com"))
                .andExpect(jsonPath("$[0].siteLabel").value("AK.LUX.NAILS — marketing site"))
                .andExpect(jsonPath("$[0].clarityProjectId").value("abc123"));
    }

    @Test
    @DisplayName("PUT forwards to the service with the current business id, the path hostname, "
            + "and the logged-in username")
    void putForwardsBusinessHostnameAndPrincipal() throws Exception {
        when(trackingConfig.update(eq(BUSINESS_ID), eq("akluxnails.com"), eq("new-id"), eq("owner")))
                .thenReturn(new TrackingConfigService.Site("akluxnails.com", "AK.LUX.NAILS — marketing site",
                        "new-id", Instant.now(), "owner"));
        Principal owner = () -> "owner";

        mvc.perform(put("/api/owner/settings/tracking/akluxnails.com")
                        .principal(owner)
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("clarityProjectId", "new-id"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clarityProjectId").value("new-id"));

        verify(trackingConfig).update(BUSINESS_ID, "akluxnails.com", "new-id", "owner");
    }
}
