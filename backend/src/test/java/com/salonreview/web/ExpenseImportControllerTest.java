package com.salonreview.web;

import com.salonreview.config.AppUserPrincipal;
import com.salonreview.domain.BankStatementImport;
import com.salonreview.domain.BankTransaction;
import com.salonreview.domain.Role;
import com.salonreview.square.ExpenseImportService;
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
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP-edge tests for {@link ExpenseImportController} — OWNER-only gating (mirrors
 * {@code ExpenseControllerTest}'s pattern) plus malformed-CSV → clear 4xx behavior. */
class ExpenseImportControllerTest {

    private ExpenseImportService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(ExpenseImportService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ExpenseImportController(service))
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
    @DisplayName("POST upload returns the new import summary")
    void uploadReturnsImportSummary() throws Exception {
        when(service.importStatement(any(), eq("owner"))).thenReturn(
                BankStatementImport.builder().id(1L).originalFilename("statement.csv").rowCount(2)
                        .status(BankStatementImport.STATUS_AWAITING_REVIEW).build());

        MockMultipartFile file = new MockMultipartFile("file", "statement.csv", "text/csv", "Date,Description,Amount\n".getBytes());

        mvc.perform(multipart("/api/owner/expenses/imports").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.rowCount").value(2));
    }

    @Test
    @DisplayName("Malformed CSV surfaces as a clear 4xx, not a 500")
    void malformedCsvReturns4xx() throws Exception {
        when(service.importStatement(any(), any()))
                .thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Couldn't find expected columns"));

        MockMultipartFile file = new MockMultipartFile("file", "bad.csv", "text/csv", "Foo,Bar\n1,2".getBytes());

        mvc.perform(multipart("/api/owner/expenses/imports").file(file))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("GET one import returns the summary plus its grouped transactions")
    void getReturnsImportDetail() throws Exception {
        when(service.getImport(1L)).thenReturn(Optional.of(
                BankStatementImport.builder().id(1L).originalFilename("statement.csv").rowCount(1)
                        .status(BankStatementImport.STATUS_AWAITING_REVIEW).build()));
        when(service.getTransactions(1L)).thenReturn(List.of(
                BankTransaction.builder().id(10L).importId(1L).transactionDate(LocalDate.of(2026, 8, 14))
                        .rawDescription("COSTCO").normalizedMerchant("COSTCO").amount(new BigDecimal("-40.00"))
                        .status(BankTransaction.STATUS_NEEDS_REVIEW).build()));

        mvc.perform(get("/api/owner/expenses/imports/{id}", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importSummary.id").value(1))
                .andExpect(jsonPath("$.transactions[0].normalizedMerchant").value("COSTCO"));
    }

    @Test
    @DisplayName("GET an unknown import returns 404")
    void getUnknownImportReturns404() throws Exception {
        when(service.getImport(999L)).thenReturn(Optional.empty());

        mvc.perform(get("/api/owner/expenses/imports/{id}", 999L)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST complete finalizes the reconciliation")
    void completeFinalizesImport() throws Exception {
        when(service.completeReconciliation(1L, "owner")).thenReturn(
                BankStatementImport.builder().id(1L).status(BankStatementImport.STATUS_COMPLETED).build());

        mvc.perform(post("/api/owner/expenses/imports/{id}/complete", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    @DisplayName("POST revert reverts the import")
    void revertRevertsImport() throws Exception {
        when(service.revertImport(1L)).thenReturn(
                BankStatementImport.builder().id(1L).status(BankStatementImport.STATUS_REVERTED).build());

        mvc.perform(post("/api/owner/expenses/imports/{id}/revert", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVERTED"));
    }
}
