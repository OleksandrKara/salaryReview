package com.salonreview.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salonreview.config.AppUserPrincipal;
import com.salonreview.domain.Role;
import com.salonreview.domain.Sop;
import com.salonreview.domain.SopAudience;
import com.salonreview.domain.SopStatus;
import com.salonreview.domain.SopVersion;
import com.salonreview.domain.SopVersionStatus;
import com.salonreview.sop.SopService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-edge tests for {@link SopController}. Standalone MockMvc with a principal — role-based 403s
 * are enforced by {@code SecurityConfig} and covered transitively (repo convention); these cover the
 * controller's own status mapping (400/403/409/422) and shape.
 */
class SopControllerTest {

    private static final Long BUSINESS_ID = 1L;

    private SopService sops;
    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        sops = mock(SopService.class);
        com.salonreview.config.CurrentBusinessContext currentBusinessContext =
                mock(com.salonreview.config.CurrentBusinessContext.class);
        when(currentBusinessContext.id()).thenReturn(BUSINESS_ID);
        mvc = MockMvcBuilders.standaloneSetup(
                        new SopController(sops, mock(com.salonreview.kb.KbAiDraftService.class), currentBusinessContext))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();

        AppUserPrincipal me = mock(AppUserPrincipal.class);
        when(me.getRole()).thenReturn(Role.OWNER);
        when(me.getUsername()).thenReturn("owner");
        when(me.getUserId()).thenReturn(1L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(me, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private SopService.SopListItem item() {
        Sop s = Sop.builder().id(1L).title("Cleaning").category("Hygiene").audience(SopAudience.PROVIDER)
                .status(SopStatus.ACTIVE).currentVersionId(100L).createdBy("owner").build();
        SopVersion v = SopVersion.builder().id(100L).sopId(1L).versionNumber(1).body("wash")
                .status(SopVersionStatus.PUBLISHED).createdBy("owner").build();
        return new SopService.SopListItem(s, v, false, null);
    }

    @Test
    @DisplayName("list returns 200 with mapped SOPs")
    void listSucceeds() throws Exception {
        when(sops.list(Role.OWNER, 1L, BUSINESS_ID)).thenReturn(List.of(item()));
        mvc.perform(get("/api/sops"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Cleaning"))
                .andExpect(jsonPath("$[0].audience").value("PROVIDER"))
                .andExpect(jsonPath("$[0].currentVersion.versionNumber").value(1));
    }

    @Test
    @DisplayName("get returns 200 with the mapped SOP — the shareable-link detail page's request")
    void getSucceeds() throws Exception {
        when(sops.getVisible(1L, Role.OWNER, 1L, BUSINESS_ID)).thenReturn(java.util.Optional.of(item()));
        mvc.perform(get("/api/sops/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Cleaning"));
    }

    @Test
    @DisplayName("get 404s when the SOP doesn't exist or isn't visible to the caller's role")
    void getNotFound() throws Exception {
        when(sops.getVisible(1L, Role.OWNER, 1L, BUSINESS_ID)).thenReturn(java.util.Optional.empty());
        mvc.perform(get("/api/sops/1")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("create with blank title → 400")
    void createBlank() throws Exception {
        mvc.perform(post("/api/sops").contentType("application/json")
                        .content(json.writeValueAsString(Map.of("title", " ", "category", "Hygiene", "audience", "PROVIDER"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("re-publishing a published version → 409")
    void publishConflict() throws Exception {
        when(sops.publish(1L, 100L, BUSINESS_ID)).thenThrow(new SopService.AlreadyPublishedException());
        mvc.perform(post("/api/sops/1/versions/100/publish")).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("out-of-audience acknowledge → 403")
    void acknowledgeForbidden() throws Exception {
        when(sops.acknowledge(anyLong(), any(), any(), any())).thenThrow(new SopService.OutOfAudienceException());
        mvc.perform(post("/api/sops/1/acknowledge")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("acknowledge with no published version → 422")
    void acknowledgeUnprocessable() throws Exception {
        when(sops.acknowledge(anyLong(), any(), any(), any())).thenThrow(new SopService.NothingToAcknowledgeException());
        mvc.perform(post("/api/sops/1/acknowledge")).andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("roster returns 200")
    void rosterSucceeds() throws Exception {
        when(sops.roster(1L, BUSINESS_ID)).thenReturn(List.of(
                new SopService.RosterEntry(10L, "prov", Role.PROVIDER, true, java.time.Instant.now())));
        mvc.perform(get("/api/sops/1/acknowledgment-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].username").value("prov"))
                .andExpect(jsonPath("$[0].acknowledged").value(true));
    }
}
