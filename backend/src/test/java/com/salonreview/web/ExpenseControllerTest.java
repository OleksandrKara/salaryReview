package com.salonreview.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.salonreview.config.AppUserPrincipal;
import com.salonreview.domain.ExpenseEntry;
import com.salonreview.domain.Role;
import com.salonreview.square.ExpenseCategoryService;
import com.salonreview.square.ExpenseService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-edge tests for {@link ExpenseController}. Standalone MockMvc with an OWNER principal —
 * this whole controller is OWNER-only via SecurityConfig's {@code /api/owner/**} catch-all,
 * covered transitively (same convention as KbArticleControllerTest).
 */
class ExpenseControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

    private ExpenseService service;
    private ExpenseCategoryService categoryService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(ExpenseService.class);
        categoryService = mock(ExpenseCategoryService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ExpenseController(service, categoryService))
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
    @DisplayName("POST creates a new expense entry using the caller's username")
    void createSavesEntry() throws Exception {
        when(service.createExpenseEntry(eq("MATERIALS"), eq(LocalDate.of(2026, 7, 1)), eq(LocalDate.of(2026, 7, 31)),
                eq(new BigDecimal("200.00")), eq("OPI restock"), eq("owner")))
                .thenReturn(ExpenseEntry.builder().id(1L).category("MATERIALS")
                        .periodStart(LocalDate.of(2026, 7, 1)).periodEnd(LocalDate.of(2026, 7, 31))
                        .amount(new BigDecimal("200.00")).note("OPI restock").enteredBy("owner").build());

        String body = JSON.writeValueAsString(new ExpenseController.ExpenseEntryRequest(
                "MATERIALS", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), new BigDecimal("200.00"), "OPI restock"));

        mvc.perform(post("/api/owner/expenses").contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("MATERIALS"))
                .andExpect(jsonPath("$.amount").value(200.00))
                .andExpect(jsonPath("$.enteredBy").value("owner"));
    }

    @Test
    @DisplayName("POST accepts an owner-added custom category")
    void createAcceptsKnownCustomCategory() throws Exception {
        when(service.createExpenseEntry(eq("CONTRACTORS"), eq(LocalDate.of(2026, 7, 1)), eq(LocalDate.of(2026, 7, 31)),
                eq(new BigDecimal("350.00")), any(), eq("owner")))
                .thenReturn(ExpenseEntry.builder().id(2L).category("CONTRACTORS")
                        .periodStart(LocalDate.of(2026, 7, 1)).periodEnd(LocalDate.of(2026, 7, 31))
                        .amount(new BigDecimal("350.00")).enteredBy("owner").build());

        String body = JSON.writeValueAsString(new ExpenseController.ExpenseEntryRequest(
                "CONTRACTORS", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), new BigDecimal("350.00"), null));

        mvc.perform(post("/api/owner/expenses").contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("CONTRACTORS"));
    }

    @Test
    @DisplayName("POST rejects an unknown category with 400")
    void createRejectsUnknownCategory() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "'BOGUS' isn't a known expense category"))
                .when(categoryService).assertValidCode("BOGUS");

        String body = JSON.writeValueAsString(new ExpenseController.ExpenseEntryRequest(
                "BOGUS", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), new BigDecimal("200.00"), null));

        mvc.perform(post("/api/owner/expenses").contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
        verify(service, never()).createExpenseEntry(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("PUT rejects an unknown category with 400")
    void updateRejectsUnknownCategory() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "'BOGUS' isn't a known expense category"))
                .when(categoryService).assertValidCode("BOGUS");

        String body = JSON.writeValueAsString(new ExpenseController.ExpenseEntryRequest(
                "BOGUS", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), new BigDecimal("150.00"), null));

        mvc.perform(put("/api/owner/expenses/{id}", 1L).contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
        verify(service, never()).updateExpenseEntry(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("GET lists every entry")
    void listReturnsEntries() throws Exception {
        when(service.listExpenseEntries()).thenReturn(List.of(
                ExpenseEntry.builder().id(1L).category("MATERIALS").periodStart(LocalDate.of(2026, 7, 1))
                        .periodEnd(LocalDate.of(2026, 7, 31)).amount(new BigDecimal("200.00")).build()));

        mvc.perform(get("/api/owner/expenses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].category").value("MATERIALS"));
    }

    @Test
    @DisplayName("PUT updates an existing entry")
    void updateReturnsUpdatedEntry() throws Exception {
        when(service.updateExpenseEntry(eq(1L), eq("RENT"), any(), any(), eq(new BigDecimal("150.00")), any()))
                .thenReturn(Optional.of(ExpenseEntry.builder().id(1L).category("RENT")
                        .periodStart(LocalDate.of(2026, 8, 1)).periodEnd(LocalDate.of(2026, 8, 31))
                        .amount(new BigDecimal("150.00")).build()));

        String body = JSON.writeValueAsString(new ExpenseController.ExpenseEntryRequest(
                "RENT", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), new BigDecimal("150.00"), null));

        mvc.perform(put("/api/owner/expenses/{id}", 1L).contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("RENT"));
    }

    @Test
    @DisplayName("PUT on an unknown id returns 404")
    void updateUnknownIdReturns404() throws Exception {
        when(service.updateExpenseEntry(eq(999L), any(), any(), any(), any(), any())).thenReturn(Optional.empty());

        String body = JSON.writeValueAsString(new ExpenseController.ExpenseEntryRequest(
                "RENT", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), new BigDecimal("150.00"), null));

        mvc.perform(put("/api/owner/expenses/{id}", 999L).contentType("application/json").content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DELETE removes an existing entry")
    void deleteReturnsNoContent() throws Exception {
        when(service.deleteExpenseEntry(1L)).thenReturn(true);

        mvc.perform(delete("/api/owner/expenses/{id}", 1L)).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("DELETE on an unknown id returns 404")
    void deleteUnknownIdReturns404() throws Exception {
        when(service.deleteExpenseEntry(999L)).thenReturn(false);

        mvc.perform(delete("/api/owner/expenses/{id}", 999L)).andExpect(status().isNotFound());
    }
}
