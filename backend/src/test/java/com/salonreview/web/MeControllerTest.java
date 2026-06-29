package com.salonreview.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salonreview.config.AiTriageProperties;
import com.salonreview.config.AppUserPrincipal;
import com.salonreview.config.RagProperties;
import com.salonreview.domain.AppUser;
import com.salonreview.domain.Language;
import com.salonreview.domain.Role;
import com.salonreview.repo.AppUserRepository;
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
    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        users = mock(AppUserRepository.class);
        mvc = MockMvcBuilders.standaloneSetup(new MeController(new AiTriageProperties(), new RagProperties(), users))
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
}
