package com.salonreview.web;

import com.salonreview.config.AppUserPrincipal;
import com.salonreview.domain.AppUser;
import com.salonreview.domain.Provider;
import com.salonreview.domain.Role;
import com.salonreview.domain.StaffDocument;
import com.salonreview.repo.AppUserRepository;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.service.StaffDocumentService;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone MockMvc with a principal — same convention as {@code StaffDocumentControllerTest}.
 * Role gating (PROVIDER/MANAGER only) is enforced by SecurityConfig and covered transitively, not
 * re-tested here; what matters here is that the person is always resolved from the authenticated
 * principal and a document belonging to someone else is never listed or downloadable.
 */
class StaffDocumentSelfControllerTest {

    private StaffDocumentService service;
    private ProviderRepository providers;
    private AppUserRepository users;
    private MockMvc mvc;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(Role role, Long userId, Long providerId) {
        AppUserPrincipal me = mock(AppUserPrincipal.class);
        when(me.getRole()).thenReturn(role);
        when(me.getUserId()).thenReturn(userId);
        when(me.getProviderId()).thenReturn(providerId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(me, null, List.of()));
    }

    @BeforeEach
    void setUp() {
        service = mock(StaffDocumentService.class);
        providers = mock(ProviderRepository.class);
        users = mock(AppUserRepository.class);
        mvc = MockMvcBuilders.standaloneSetup(new StaffDocumentSelfController(service, providers, users))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @Test
    @DisplayName("GET (provider) lists only that provider's own documents")
    void listForProvider() throws Exception {
        loginAs(Role.PROVIDER, 1L, 10L);
        StaffDocument doc = StaffDocument.builder().id(1L).providerId(10L).documentType("License")
                .fileName("lic.pdf").expirationDate(LocalDate.now().plusDays(5))
                .createdBy("owner1").createdAt(Instant.now()).build();
        when(service.listForProvider(10L)).thenReturn(List.of(doc));
        when(providers.findById(10L)).thenReturn(Optional.of(Provider.builder().id(10L).displayName("Jane Doe").build()));

        mvc.perform(get("/api/staff-documents/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].personType").value("PROVIDER"))
                .andExpect(jsonPath("$[0].personName").value("Jane Doe"));
    }

    @Test
    @DisplayName("GET (manager) lists only that manager's own documents")
    void listForManager() throws Exception {
        loginAs(Role.MANAGER, 5L, null);
        StaffDocument doc = StaffDocument.builder().id(2L).appUserId(5L).documentType("NDA")
                .fileName("nda.pdf").expirationDate(LocalDate.now().plusYears(1))
                .createdBy("owner1").createdAt(Instant.now()).build();
        when(service.listForManager(5L)).thenReturn(List.of(doc));
        when(users.findById(5L)).thenReturn(Optional.of(AppUser.builder().id(5L).username("mgr1").role(Role.MANAGER).build()));

        mvc.perform(get("/api/staff-documents/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].personType").value("MANAGER"))
                .andExpect(jsonPath("$[0].personName").value("mgr1"));
    }

    @Test
    @DisplayName("GET download serves a document that belongs to the caller")
    void downloadOwnDocument() throws Exception {
        loginAs(Role.PROVIDER, 1L, 10L);
        StaffDocument doc = StaffDocument.builder().id(1L).providerId(10L).documentType("License")
                .fileName("lic.pdf").contentType("application/pdf").fileData(new byte[]{9, 9, 9})
                .expirationDate(LocalDate.now().plusYears(1)).createdBy("owner1").createdAt(Instant.now()).build();
        when(service.get(1L)).thenReturn(Optional.of(doc));

        mvc.perform(get("/api/staff-documents/me/1/download"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"lic.pdf\""));
    }

    @Test
    @DisplayName("GET download 404s for a document belonging to a different provider")
    void downloadRejectsSomeoneElsesDocument() throws Exception {
        loginAs(Role.PROVIDER, 1L, 10L);
        StaffDocument doc = StaffDocument.builder().id(1L).providerId(99L).documentType("License")
                .fileName("lic.pdf").contentType("application/pdf").fileData(new byte[]{9, 9, 9})
                .expirationDate(LocalDate.now().plusYears(1)).createdBy("owner1").createdAt(Instant.now()).build();
        when(service.get(1L)).thenReturn(Optional.of(doc));

        mvc.perform(get("/api/staff-documents/me/1/download"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET download 404s for a document belonging to a different manager")
    void downloadRejectsSomeoneElsesManagerDocument() throws Exception {
        loginAs(Role.MANAGER, 5L, null);
        StaffDocument doc = StaffDocument.builder().id(3L).appUserId(6L).documentType("NDA")
                .fileName("nda.pdf").contentType("application/pdf").fileData(new byte[]{1})
                .expirationDate(LocalDate.now().plusYears(1)).createdBy("owner1").createdAt(Instant.now()).build();
        when(service.get(3L)).thenReturn(Optional.of(doc));

        mvc.perform(get("/api/staff-documents/me/3/download"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET download 404s when the document doesn't exist at all")
    void downloadMissing() throws Exception {
        loginAs(Role.PROVIDER, 1L, 10L);
        when(service.get(99L)).thenReturn(Optional.empty());

        mvc.perform(get("/api/staff-documents/me/99/download"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET (provider with no linked providerId) is rejected rather than resolving to null")
    void listRejectsUnlinkedProvider() throws Exception {
        loginAs(Role.PROVIDER, 1L, null);

        mvc.perform(get("/api/staff-documents/me")).andExpect(status().isForbidden());
        verify(service, org.mockito.Mockito.never()).listForProvider(any());
    }
}
