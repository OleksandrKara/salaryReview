package com.salonreview.web;

import com.salonreview.config.AppUserPrincipal;
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
import org.springframework.mock.web.MockMultipartFile;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone MockMvc with a principal — same convention as {@code SopControllerTest}. Role gating
 * (OWNER-only, falls under the {@code /api/owner/**} catch-all) is enforced by SecurityConfig and
 * covered transitively, not re-tested here.
 */
class StaffDocumentControllerTest {

    private StaffDocumentService service;
    private ProviderRepository providers;
    private AppUserRepository users;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(StaffDocumentService.class);
        providers = mock(ProviderRepository.class);
        users = mock(AppUserRepository.class);
        mvc = MockMvcBuilders.standaloneSetup(new StaffDocumentController(service, providers, users))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();

        AppUserPrincipal me = mock(AppUserPrincipal.class);
        when(me.getRole()).thenReturn(Role.OWNER);
        when(me.getUsername()).thenReturn("owner1");
        when(me.getUserId()).thenReturn(1L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(me, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("GET lists documents with resolved provider/manager names and status")
    void listResolvesNames() throws Exception {
        StaffDocument doc = StaffDocument.builder().id(1L).providerId(10L).documentType("License")
                .fileName("lic.pdf").expirationDate(LocalDate.now().plusDays(5))
                .createdBy("owner1").createdAt(Instant.now()).build();
        when(service.listAll()).thenReturn(List.of(doc));
        when(providers.findAllById(any())).thenReturn(List.of(
                Provider.builder().id(10L).displayName("Jane Doe").build()));
        when(users.findAllById(any())).thenReturn(List.of());

        mvc.perform(get("/api/owner/staff-documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].personType").value("PROVIDER"))
                .andExpect(jsonPath("$[0].personName").value("Jane Doe"))
                .andExpect(jsonPath("$[0].status").value("EXPIRING_SOON"));
    }

    @Test
    @DisplayName("POST (multipart) creates a document for a provider and returns its resolved DTO")
    void createForProvider() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "lic.pdf", "application/pdf", new byte[]{1, 2, 3});
        StaffDocument saved = StaffDocument.builder().id(1L).providerId(10L).documentType("License")
                .fileName("lic.pdf").expirationDate(LocalDate.of(2027, 1, 1))
                .createdBy("owner1").createdAt(Instant.now()).build();
        when(service.create(eq(10L), isNull(), eq("License"), isNull(), eq(LocalDate.of(2027, 1, 1)),
                eq("lic.pdf"), eq("application/pdf"), any(), eq("owner1"))).thenReturn(saved);
        when(providers.findById(10L)).thenReturn(Optional.of(Provider.builder().id(10L).displayName("Jane Doe").build()));

        mvc.perform(multipart("/api/owner/staff-documents")
                        .file(file)
                        .param("providerId", "10")
                        .param("documentType", "License")
                        .param("expirationDate", "2027-01-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.personType").value("PROVIDER"))
                .andExpect(jsonPath("$.personName").value("Jane Doe"));
    }

    @Test
    @DisplayName("PATCH updates expiration date and/or type/label, returning the resolved DTO")
    void updateBehavior() throws Exception {
        StaffDocument updated = StaffDocument.builder().id(1L).providerId(10L).documentType("Insurance")
                .label(null).fileName("lic.pdf").expirationDate(LocalDate.of(2099, 1, 1))
                .createdBy("owner1").createdAt(Instant.now()).build();
        when(service.update(eq(1L), eq(LocalDate.of(2099, 1, 1)), eq("Insurance"), eq("")))
                .thenReturn(Optional.of(updated));
        when(providers.findById(10L)).thenReturn(Optional.of(Provider.builder().id(10L).displayName("Jane Doe").build()));

        mvc.perform(patch("/api/owner/staff-documents/1")
                        .contentType("application/json")
                        .content("{\"expirationDate\":\"2099-01-01\",\"documentType\":\"Insurance\",\"label\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expirationDate").value("2099-01-01"))
                .andExpect(jsonPath("$.documentType").value("Insurance"))
                .andExpect(jsonPath("$.personName").value("Jane Doe"));
    }

    @Test
    @DisplayName("PATCH 404s when the document doesn't exist")
    void updateMissing() throws Exception {
        when(service.update(eq(99L), any(), any(), any())).thenReturn(Optional.empty());

        mvc.perform(patch("/api/owner/staff-documents/99")
                        .contentType("application/json")
                        .content("{\"expirationDate\":\"2099-01-01\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /{id}/download serves the stored bytes with the stored content type and filename")
    void download() throws Exception {
        StaffDocument doc = StaffDocument.builder().id(1L).providerId(10L).documentType("License")
                .fileName("lic.pdf").contentType("application/pdf").fileData(new byte[]{9, 9, 9})
                .expirationDate(LocalDate.now().plusYears(1)).createdBy("owner1").createdAt(Instant.now()).build();
        when(service.get(1L)).thenReturn(Optional.of(doc));

        mvc.perform(get("/api/owner/staff-documents/1/download"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"lic.pdf\""));
    }

    @Test
    @DisplayName("GET /{id}/download 404s when the document doesn't exist")
    void downloadMissing() throws Exception {
        when(service.get(99L)).thenReturn(Optional.empty());

        mvc.perform(get("/api/owner/staff-documents/99/download"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DELETE removes an existing document (204) and 404s for a missing one")
    void deleteBehavior() throws Exception {
        when(service.delete(1L)).thenReturn(true);
        when(service.delete(99L)).thenReturn(false);

        mvc.perform(delete("/api/owner/staff-documents/1")).andExpect(status().isNoContent());
        mvc.perform(delete("/api/owner/staff-documents/99")).andExpect(status().isNotFound());
    }
}
