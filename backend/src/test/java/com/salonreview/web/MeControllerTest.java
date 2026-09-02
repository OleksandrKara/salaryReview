package com.salonreview.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salonreview.config.AiSeoAdvisorProperties;
import com.salonreview.config.AiTriageProperties;
import com.salonreview.config.AppUserPrincipal;
import com.salonreview.config.BusinessFeatureService;
import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.config.RagProperties;
import com.salonreview.domain.AppUser;
import com.salonreview.domain.Language;
import com.salonreview.domain.Role;
import com.salonreview.repo.AppUserRepository;
import com.salonreview.repo.BusinessMembershipRepository;
import com.salonreview.repo.BusinessRepository;
import com.salonreview.repo.PlatformAdminRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Edge tests for {@link MeController}: /api/me language echo + the set endpoint's validation/save. */
class MeControllerTest {

    private AppUserRepository users;
    private PlatformAdminRepository platformAdmins;
    private BusinessMembershipRepository memberships;
    private BusinessRepository businesses;
    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        users = mock(AppUserRepository.class);
        CurrentBusinessContext currentBusinessContext = mock(CurrentBusinessContext.class);
        when(currentBusinessContext.id()).thenReturn(1L);
        platformAdmins = mock(PlatformAdminRepository.class);
        memberships = mock(BusinessMembershipRepository.class);
        when(memberships.findByUserId(1L)).thenReturn(List.of());
        businesses = mock(BusinessRepository.class);
        BusinessFeatureService businessFeatures = mock(BusinessFeatureService.class);
        mvc = MockMvcBuilders.standaloneSetup(new MeController(new AiTriageProperties(), new AiSeoAdvisorProperties(),
                        new RagProperties(), users, currentBusinessContext, platformAdmins, memberships, businesses,
                        businessFeatures))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();

        AppUserPrincipal me = mock(AppUserPrincipal.class);
        when(me.getUserId()).thenReturn(1L);
        when(me.getUsername()).thenReturn("manager");
        when(me.getRole()).thenReturn(Role.MANAGER);
        when(me.getProviderId()).thenReturn(null);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(me, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private AppUser user(Language lang) {
        return AppUser.builder().id(1L).username("manager").passwordHash("x").role(Role.MANAGER)
                .preferredLanguage(lang).build();
    }

    @Test
    @DisplayName("/api/me echoes the stored preferred language")
    void meEchoesLanguage() throws Exception {
        when(users.findById(1L)).thenReturn(Optional.of(user(Language.RU)));
        mvc.perform(get("/api/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferredLanguage").value("RU"));
    }

    @Test
    @DisplayName("/api/me reports null when the user hasn't chosen")
    void meNullWhenUnset() throws Exception {
        when(users.findById(1L)).thenReturn(Optional.of(user(null)));
        mvc.perform(get("/api/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferredLanguage").doesNotExist());
    }

    @Test
    @DisplayName("setting a valid language saves it")
    void setLanguageSaves() throws Exception {
        when(users.findById(1L)).thenReturn(Optional.of(user(null)));
        mvc.perform(post("/api/me/language").contentType("application/json")
                        .content(json.writeValueAsString(Map.of("language", "ru")))) // lowercase tolerated
                .andExpect(status().isOk());

        ArgumentCaptor<AppUser> cap = ArgumentCaptor.forClass(AppUser.class);
        verify(users).save(cap.capture());
        assertThat(cap.getValue().getPreferredLanguage()).isEqualTo(Language.RU);
    }

    @Test
    @DisplayName("an unknown language is rejected and nothing is saved")
    void setLanguageRejectsUnknown() throws Exception {
        mvc.perform(post("/api/me/language").contentType("application/json")
                        .content(json.writeValueAsString(Map.of("language", "FR"))))
                .andExpect(status().isBadRequest());
        verify(users, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("2026-08-18 (Phase 6.1/6.2): /api/me reports the effective activeBusinessId from "
            + "CurrentBusinessContext, session-switch-aware, not the login-time default")
    void meReportsCurrentBusinessContextsActiveBusinessId() throws Exception {
        when(users.findById(1L)).thenReturn(Optional.of(user(null)));
        mvc.perform(get("/api/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeBusinessId").value(1));
    }

    @Test
    @DisplayName("2026-08-18: a platform_admin's switcher list is every active business, regardless "
            + "of having a business_membership row for it")
    void platformAdminSeesEveryActiveBusiness() throws Exception {
        when(users.findById(1L)).thenReturn(Optional.of(user(null)));
        when(platformAdmins.existsById(1L)).thenReturn(true);
        when(businesses.findAllByActiveTrue()).thenReturn(List.of(
                com.salonreview.domain.Business.builder().id(1L).name("AK.LUX.NAILS").active(true).build(),
                com.salonreview.domain.Business.builder().id(2L).name("AK PMU").active(true).build()));

        mvc.perform(get("/api/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businesses.length()").value(2))
                .andExpect(jsonPath("$.businesses[1].name").value("AK PMU"));
        verify(memberships, never()).findByUserId(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("Phase 4.3: features.ragEnabled/aiTriageEnabled are business-scoped on top of the "
            + "deployment-level flag, not just deployment-wide")
    void featuresAreBusinessScoped() throws Exception {
        AiTriageProperties aiTriage = new AiTriageProperties();
        aiTriage.setEnabled(true);
        RagProperties rag = new RagProperties();
        rag.setEnabled(true);
        CurrentBusinessContext ctx = mock(CurrentBusinessContext.class);
        when(ctx.id()).thenReturn(2L);
        BusinessFeatureService businessFeatures = mock(BusinessFeatureService.class);
        when(businessFeatures.isEnabled(2L, BusinessFeatureService.RAG_ENABLED)).thenReturn(false);
        when(businessFeatures.isEnabled(2L, BusinessFeatureService.AI_TRIAGE_ENABLED)).thenReturn(false);
        when(users.findById(1L)).thenReturn(Optional.of(user(null)));
        MockMvc scopedMvc = MockMvcBuilders.standaloneSetup(new MeController(aiTriage, new AiSeoAdvisorProperties(), rag,
                        users, ctx, platformAdmins, memberships, businesses, businessFeatures))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();

        // Deployment-level flags are both on, but business 2 has no business_feature row for either
        // key — the effective, reported value must still be false.
        scopedMvc.perform(get("/api/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.features.ragEnabled").value(false))
                .andExpect(jsonPath("$.features.aiTriageEnabled").value(false));
    }

    @Test
    @DisplayName("2026-08-18: a non-platform-admin's switcher list is only their own real membership(s)")
    void regularUserSeesOnlyOwnMemberships() throws Exception {
        when(users.findById(1L)).thenReturn(Optional.of(user(null)));
        when(platformAdmins.existsById(1L)).thenReturn(false);
        when(memberships.findByUserId(1L)).thenReturn(List.of(
                com.salonreview.domain.BusinessMembership.builder().businessId(1L).userId(1L)
                        .role(Role.MANAGER).build()));
        when(businesses.findById(1L)).thenReturn(Optional.of(
                com.salonreview.domain.Business.builder().id(1L).name("AK.LUX.NAILS").active(true).build()));

        mvc.perform(get("/api/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businesses.length()").value(1))
                .andExpect(jsonPath("$.businesses[0].name").value("AK.LUX.NAILS"));
        verify(businesses, never()).findAllByActiveTrue();
    }
}
