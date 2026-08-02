package com.salonreview.web;

import com.salonreview.config.AppUserPrincipal;
import com.salonreview.domain.MerchantRule;
import com.salonreview.domain.Role;
import com.salonreview.square.MerchantRuleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP-edge tests for {@link MerchantRuleController} — OWNER-only gating. */
class MerchantRuleControllerTest {

    private MerchantRuleService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(MerchantRuleService.class);
        mvc = MockMvcBuilders.standaloneSetup(new MerchantRuleController(service))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();

        AppUserPrincipal me = mock(AppUserPrincipal.class);
        when(me.getRole()).thenReturn(Role.OWNER);
        when(me.getUsername()).thenReturn("owner");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(me, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("GET lists every rule")
    void listReturnsRules() throws Exception {
        when(service.listAll()).thenReturn(List.of(
                MerchantRule.builder().id(1L).ruleType("MERCHANT").normalizedMerchant("COSTCO")
                        .category("MATERIALS").active(true).createdAt(Instant.now()).updatedAt(Instant.now()).build()));

        mvc.perform(get("/api/owner/expenses/rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].normalizedMerchant").value("COSTCO"));
    }

    @Test
    @DisplayName("PUT updates a rule")
    void updateReturnsUpdatedRule() throws Exception {
        when(service.update(eq(1L), eq("UTILITIES"), any(), any(), any(), any())).thenReturn(Optional.of(
                MerchantRule.builder().id(1L).ruleType("MERCHANT").normalizedMerchant("COSTCO")
                        .category("UTILITIES").active(true).createdAt(Instant.now()).updatedAt(Instant.now()).build()));

        mvc.perform(put("/api/owner/expenses/rules/{id}", 1L).contentType("application/json")
                        .content("{\"category\":\"UTILITIES\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("UTILITIES"));
    }

    @Test
    @DisplayName("PUT on an unknown id returns 404")
    void updateUnknownIdReturns404() throws Exception {
        when(service.update(eq(999L), any(), any(), any(), any(), any())).thenReturn(Optional.empty());

        mvc.perform(put("/api/owner/expenses/rules/{id}", 999L).contentType("application/json")
                        .content("{\"category\":\"UTILITIES\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DELETE removes an existing rule")
    void deleteReturnsNoContent() throws Exception {
        when(service.delete(1L)).thenReturn(true);

        mvc.perform(delete("/api/owner/expenses/rules/{id}", 1L)).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("DELETE on an unknown id returns 404")
    void deleteUnknownIdReturns404() throws Exception {
        when(service.delete(999L)).thenReturn(false);

        mvc.perform(delete("/api/owner/expenses/rules/{id}", 999L)).andExpect(status().isNotFound());
    }
}
