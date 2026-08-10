package com.salonreview.square;

import com.salonreview.domain.BankStatementImport;
import com.salonreview.domain.BankTransaction;
import com.salonreview.domain.ExpenseEntry;
import com.salonreview.repo.BankStatementImportRepository;
import com.salonreview.repo.BankTransactionRepository;
import com.salonreview.repo.MerchantRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** openspec design.md D3/D7/D10, tasks.md 6.4. */
class ExpenseImportServiceTest {

    private BankStatementImportRepository imports;
    private BankTransactionRepository transactions;
    private MerchantRuleRepository merchantRules;
    private CsvStatementParser parser;
    private MerchantRuleEngine ruleEngine;
    private MerchantRuleService merchantRuleService;
    private PayrollDisbursementDetector payrollDetector;
    private ExpenseService expenseService;
    private ExpenseImportService service;

    private final AtomicLong txnIdSeq = new AtomicLong(1);

    @BeforeEach
    void setUp() {
        imports = mock(BankStatementImportRepository.class);
        transactions = mock(BankTransactionRepository.class);
        merchantRules = mock(MerchantRuleRepository.class);
        parser = mock(CsvStatementParser.class);
        // No opening/closing balance by default (mirrors extractBalances()'s own graceful-null
        // behavior for a CSV export whose format doesn't include the balance block) — individual
        // tests can override to exercise the balance-capture path.
        when(parser.extractBalances(any())).thenReturn(new CsvStatementParser.Balances(null, null));
        ruleEngine = mock(MerchantRuleEngine.class);
        merchantRuleService = mock(MerchantRuleService.class);
        payrollDetector = mock(PayrollDisbursementDetector.class);
        expenseService = mock(ExpenseService.class);
        service = new ExpenseImportService(imports, transactions, merchantRules, parser, ruleEngine,
                merchantRuleService, payrollDetector, expenseService);

        when(imports.save(any())).thenAnswer(inv -> {
            BankStatementImport i = inv.getArgument(0);
            if (i.getId() == null) i.setId(1L);
            return i;
        });
        when(transactions.save(any())).thenAnswer(inv -> {
            BankTransaction t = inv.getArgument(0);
            if (t.getId() == null) t.setId(txnIdSeq.getAndIncrement());
            return t;
        });
        when(payrollDetector.suggest(anyString())).thenReturn(Optional.empty());
        when(transactions.findNonRevertedDuplicate(anyString(), anyInt())).thenReturn(Optional.empty());
    }

    private static CsvStatementParser.ParsedTransaction fixture(String merchant, BigDecimal amount, String fp) {
        return new CsvStatementParser.ParsedTransaction(LocalDate.of(2026, 8, 14), merchant + " raw",
                amount, merchant, merchant, fp, 0);
    }

    @Test
    @DisplayName("importStatement parses, persists the import, and persists one transaction per row")
    void importStatementPersistsRoundTrip() {
        List<CsvStatementParser.ParsedTransaction> parsed = List.of(
                fixture("COSTCO", new BigDecimal("-40.00"), "fp1"),
                fixture("TARGET", new BigDecimal("-20.00"), "fp2"));
        when(parser.parse(any())).thenReturn(parsed);
        when(ruleEngine.evaluate(any())).thenReturn(MerchantRuleEngine.MatchResult.unknown());

        MockMultipartFile file = new MockMultipartFile("file", "statement.csv", "text/csv", "irrelevant".getBytes());
        BankStatementImport imp = service.importStatement(file, "owner");

        assertThat(imp.getRowCount()).isEqualTo(2);
        assertThat(imp.getStatus()).isEqualTo(BankStatementImport.STATUS_AWAITING_REVIEW);
        verify(transactions, times(2)).save(any());
    }

    @Test
    @DisplayName("A positive-amount row (money in) is auto-excluded as DEPOSIT without ever reaching the rule engine")
    void positiveAmountRowIsAutoExcludedAsDeposit() {
        List<CsvStatementParser.ParsedTransaction> parsed = List.of(
                fixture("SQUAREINC", new BigDecimal("544.39"), "fp-deposit"),
                fixture("COSTCO", new BigDecimal("-40.00"), "fp-expense"));
        when(parser.parse(any())).thenReturn(parsed);
        when(ruleEngine.evaluate(any())).thenReturn(MerchantRuleEngine.MatchResult.unknown());

        MockMultipartFile file = new MockMultipartFile("file", "statement.csv", "text/csv", "irrelevant".getBytes());
        service.importStatement(file, "owner");

        ArgumentCaptor<BankTransaction> captor = ArgumentCaptor.forClass(BankTransaction.class);
        verify(transactions, times(2)).save(captor.capture());
        BankTransaction deposit = captor.getAllValues().get(0);
        assertThat(deposit.getStatus()).isEqualTo(BankTransaction.STATUS_EXCLUDED);
        assertThat(deposit.getExcludedReason()).isEqualTo(BankTransaction.EXCLUDE_DEPOSIT);
        // the rule engine is never even asked about the deposit row — only the expense row
        verify(ruleEngine, times(1)).evaluate(any());
    }

    @Test
    @DisplayName("A duplicate against a pre-existing fixture is marked DUPLICATE, not categorized")
    void duplicateDetectionAgainstExistingFixture() {
        when(parser.parse(any())).thenReturn(List.of(fixture("COSTCO", new BigDecimal("-40.00"), "fp1")));
        BankTransaction existing = BankTransaction.builder().id(99L).importId(5L).build();
        when(transactions.findNonRevertedDuplicate("fp1", 0)).thenReturn(Optional.of(existing));

        MockMultipartFile file = new MockMultipartFile("file", "statement.csv", "text/csv", "irrelevant".getBytes());
        service.importStatement(file, "owner");

        ArgumentCaptor<BankTransaction> captor = ArgumentCaptor.forClass(BankTransaction.class);
        verify(transactions).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(BankTransaction.STATUS_DUPLICATE);
        assertThat(captor.getValue().getDuplicateOfTransactionId()).isEqualTo(99L);
        verifyNoInteractions(ruleEngine);
    }

    @Test
    @DisplayName("completeReconciliation creates exactly one expense_entries row per eligible transaction, with the correct sign")
    void completeReconciliationCreatesEntriesForEligibleTransactionsOnly() {
        BankStatementImport imp = BankStatementImport.builder().id(1L).status(BankStatementImport.STATUS_AWAITING_REVIEW).build();
        when(imports.findById(1L)).thenReturn(Optional.of(imp));

        BankTransaction autoMatched = BankTransaction.builder().id(1L).importId(1L)
                .transactionDate(LocalDate.of(2026, 8, 14)).normalizedMerchant("COSTCO")
                .amount(new BigDecimal("-40.00")).status(BankTransaction.STATUS_AUTO_MATCHED).category("MATERIALS").build();
        BankTransaction excluded = BankTransaction.builder().id(2L).importId(1L)
                .transactionDate(LocalDate.of(2026, 8, 15)).normalizedMerchant("MANAGERPAYOUT")
                .amount(new BigDecimal("-500.00")).status(BankTransaction.STATUS_EXCLUDED).excludedReason("PAYROLL").build();
        BankTransaction duplicate = BankTransaction.builder().id(3L).importId(1L)
                .transactionDate(LocalDate.of(2026, 8, 16)).normalizedMerchant("NETFLIX")
                .amount(new BigDecimal("-9.99")).status(BankTransaction.STATUS_DUPLICATE).build();
        when(transactions.findByImportIdOrderByTransactionDateAsc(1L)).thenReturn(new ArrayList<>(List.of(autoMatched, excluded, duplicate)));

        when(expenseService.createExpenseEntry(eq("MATERIALS"), eq(LocalDate.of(2026, 8, 14)), eq(LocalDate.of(2026, 8, 14)),
                eq(new BigDecimal("40.00")), eq("COSTCO"), eq("owner")))
                .thenReturn(ExpenseEntry.builder().id(500L).build());

        BankStatementImport completed = service.completeReconciliation(1L, "owner");

        assertThat(completed.getStatus()).isEqualTo(BankStatementImport.STATUS_COMPLETED);
        verify(expenseService, times(1)).createExpenseEntry(any(), any(), any(), any(), any(), any());
        assertThat(autoMatched.getLinkedExpenseEntryId()).isEqualTo(500L);
        assertThat(excluded.getLinkedExpenseEntryId()).isNull();
        assertThat(duplicate.getLinkedExpenseEntryId()).isNull();
    }

    @Test
    @DisplayName("Completing an already-completed import is rejected")
    void completingAlreadyCompletedImportRejected() {
        BankStatementImport imp = BankStatementImport.builder().id(1L).status(BankStatementImport.STATUS_COMPLETED).build();
        when(imports.findById(1L)).thenReturn(Optional.of(imp));

        assertThatThrownBy(() -> service.completeReconciliation(1L, "owner"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("revertImport deletes only the expense_entries rows it created and resets its own transactions")
    void revertImportDeletesOnlyItsOwnEntries() {
        BankStatementImport imp = BankStatementImport.builder().id(1L).status(BankStatementImport.STATUS_COMPLETED).build();
        when(imports.findById(1L)).thenReturn(Optional.of(imp));

        BankTransaction linked = BankTransaction.builder().id(1L).importId(1L)
                .status(BankTransaction.STATUS_AUTO_MATCHED).linkedExpenseEntryId(500L).build();
        when(transactions.findByImportIdAndLinkedExpenseEntryIdIsNotNull(1L)).thenReturn(List.of(linked));
        when(expenseService.deleteExpenseEntry(500L)).thenReturn(true);

        BankStatementImport reverted = service.revertImport(1L);

        assertThat(reverted.getStatus()).isEqualTo(BankStatementImport.STATUS_REVERTED);
        verify(expenseService).deleteExpenseEntry(500L);
        assertThat(linked.getStatus()).isEqualTo(BankTransaction.STATUS_UNMATCHED);
        assertThat(linked.getLinkedExpenseEntryId()).isNull();
        // no other import's or manually-entered expense_entries rows are ever touched — this
        // service only ever deletes ids it finds via findByImportIdAndLinkedExpenseEntryIdIsNotNull
        verify(expenseService, times(1)).deleteExpenseEntry(any());
    }

    @Test
    @DisplayName("Reverting a non-completed import is rejected")
    void revertingNonCompletedImportRejected() {
        BankStatementImport imp = BankStatementImport.builder().id(1L).status(BankStatementImport.STATUS_AWAITING_REVIEW).build();
        when(imports.findById(1L)).thenReturn(Optional.of(imp));

        assertThatThrownBy(() -> service.revertImport(1L)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("deleteImport removes an AWAITING_REVIEW import's transactions and the import itself")
    void deleteImportRemovesUnreconciledImport() {
        BankStatementImport imp = BankStatementImport.builder().id(1L).status(BankStatementImport.STATUS_AWAITING_REVIEW).build();
        when(imports.findById(1L)).thenReturn(Optional.of(imp));

        service.deleteImport(1L);

        verify(transactions).clearDuplicateReferencesInto(1L);
        verify(transactions).deleteByImportId(1L);
        verify(imports).delete(imp);
    }

    @Test
    @DisplayName("deleteImport also allows a REVERTED import (no live financial effect left)")
    void deleteImportAllowsRevertedImport() {
        BankStatementImport imp = BankStatementImport.builder().id(2L).status(BankStatementImport.STATUS_REVERTED).build();
        when(imports.findById(2L)).thenReturn(Optional.of(imp));

        service.deleteImport(2L);

        verify(imports).delete(imp);
    }

    @Test
    @DisplayName("deleteImport rejects a COMPLETED import — it must be reverted first")
    void deleteImportRejectsCompletedImport() {
        BankStatementImport imp = BankStatementImport.builder().id(3L).status(BankStatementImport.STATUS_COMPLETED).build();
        when(imports.findById(3L)).thenReturn(Optional.of(imp));

        assertThatThrownBy(() -> service.deleteImport(3L)).isInstanceOf(ResponseStatusException.class);
        verify(imports, never()).delete(any());
        verify(transactions, never()).deleteByImportId(any());
    }

    @Test
    @DisplayName("Reviewing with non-empty rememberKeywords creates a keyword rule, not a plain-merchant rule")
    void reviewWithRememberKeywordsCreatesKeywordRule() {
        BankTransaction txn = BankTransaction.builder().id(10L).normalizedMerchant("PAYSEND0630").build();
        when(transactions.findById(10L)).thenReturn(Optional.of(txn));
        com.salonreview.domain.MerchantRule createdRule = com.salonreview.domain.MerchantRule.builder().id(77L).build();
        when(merchantRuleService.rememberKeywords(any(), eq("OTHER"), eq(10L), eq("owner"))).thenReturn(createdRule);

        service.reviewTransaction(10L, "OTHER", null, false, false, List.of("PAYSEND", "DEBIT CARD"), "owner");

        verify(merchantRuleService).rememberKeywords(List.of("PAYSEND", "DEBIT CARD"), "OTHER", 10L, "owner");
        verify(merchantRuleService, never()).rememberForMerchant(any(), any(), any(), anyBoolean(), any());
        assertThat(txn.getMatchedRuleId()).isEqualTo(77L);
    }

    @Test
    @DisplayName("Reviewing with rememberForMerchant and no keywords still creates a plain-merchant rule")
    void reviewWithRememberForMerchantCreatesMerchantRule() {
        BankTransaction txn = BankTransaction.builder().id(11L).normalizedMerchant("COSTCO").build();
        when(transactions.findById(11L)).thenReturn(Optional.of(txn));
        com.salonreview.domain.MerchantRule createdRule = com.salonreview.domain.MerchantRule.builder().id(78L).build();
        when(merchantRuleService.rememberForMerchant("COSTCO", "MATERIALS", 11L, false, "owner")).thenReturn(createdRule);

        service.reviewTransaction(11L, "MATERIALS", null, true, false, List.of(), "owner");

        verify(merchantRuleService).rememberForMerchant("COSTCO", "MATERIALS", 11L, false, "owner");
        verify(merchantRuleService, never()).rememberKeywords(any(), any(), any(), any());
        assertThat(txn.getMatchedRuleId()).isEqualTo(78L);
    }

    @Test
    @DisplayName("Creating a new rule via review immediately auto-categorizes other NEEDS_REVIEW siblings in the same import that now match it")
    void reviewWithRememberForMerchantReapliesToSiblingsInSameImport() {
        BankTransaction reviewed = BankTransaction.builder().id(20L).importId(500L)
                .normalizedMerchant("AMAZON").status(BankTransaction.STATUS_NEEDS_REVIEW).build();
        BankTransaction siblingMatches = BankTransaction.builder().id(21L).importId(500L)
                .normalizedMerchant("AMAZON").status(BankTransaction.STATUS_NEEDS_REVIEW)
                .transactionDate(LocalDate.of(2026, 8, 1)).rawDescription("AMAZON.COM*ABC123")
                .amount(new BigDecimal("-15.00")).fingerprint("fp-21").occurrenceIndex(0).build();
        BankTransaction siblingAlreadyReviewed = BankTransaction.builder().id(22L).importId(500L)
                .normalizedMerchant("AMAZON").status(BankTransaction.STATUS_REVIEWED).build();

        when(transactions.findById(20L)).thenReturn(Optional.of(reviewed));
        when(transactions.findByImportIdOrderByTransactionDateAsc(500L))
                .thenReturn(List.of(reviewed, siblingMatches, siblingAlreadyReviewed));
        com.salonreview.domain.MerchantRule createdRule = com.salonreview.domain.MerchantRule.builder().id(90L).build();
        when(merchantRuleService.rememberForMerchant("AMAZON", "SUPPLIES", 20L, false, "owner")).thenReturn(createdRule);
        when(ruleEngine.evaluate(any())).thenReturn(
                new MerchantRuleEngine.MatchResult("SUPPLIES", new BigDecimal("0.90"), "Matched because: Normalized Merchant = AMAZON", 90L, true));

        service.reviewTransaction(20L, "SUPPLIES", null, true, false, List.of(), "owner");

        assertThat(siblingMatches.getStatus()).isEqualTo(BankTransaction.STATUS_AUTO_MATCHED);
        assertThat(siblingMatches.getCategory()).isEqualTo("SUPPLIES");
        assertThat(siblingMatches.getMatchedRuleId()).isEqualTo(90L);
        // Already-reviewed sibling and rows from other imports must never be touched.
        assertThat(siblingAlreadyReviewed.getStatus()).isEqualTo(BankTransaction.STATUS_REVIEWED);
        verify(transactions, never()).findByImportIdOrderByTransactionDateAsc(999L);
    }

    @Test
    @DisplayName("bankBalanceForMonth uses the earliest overlapping import's opening balance and the latest's closing balance")
    void bankBalanceForMonthUsesEarliestOpeningAndLatestClosing() {
        LocalDate from = LocalDate.of(2026, 6, 1), to = LocalDate.of(2026, 6, 30);
        BankStatementImport earlier = BankStatementImport.builder().id(1L)
                .openingBalance(new BigDecimal("9192.33")).closingBalance(new BigDecimal("9000.00")).build();
        BankStatementImport later = BankStatementImport.builder().id(2L)
                .openingBalance(new BigDecimal("9000.00")).closingBalance(new BigDecimal("8550.84")).build();
        when(imports.findCompletedOverlapping(from, to)).thenReturn(List.of(earlier, later));

        ExpenseImportService.BankBalance balance = service.bankBalanceForMonth(from, to);

        assertThat(balance).isNotNull();
        assertThat(balance.opening()).isEqualByComparingTo("9192.33");
        assertThat(balance.closing()).isEqualByComparingTo("8550.84");
    }

    @Test
    @DisplayName("bankBalanceForMonth is null when nothing overlapping captured a balance")
    void bankBalanceForMonthNullWhenNoBalanceCaptured() {
        LocalDate from = LocalDate.of(2025, 1, 1), to = LocalDate.of(2025, 1, 31);
        BankStatementImport noBalance = BankStatementImport.builder().id(3L).build();
        when(imports.findCompletedOverlapping(from, to)).thenReturn(List.of(noBalance));

        assertThat(service.bankBalanceForMonth(from, to)).isNull();
    }

    @Test
    @DisplayName("bankBalanceForMonth is null when there's no overlapping completed import at all")
    void bankBalanceForMonthNullWhenNoOverlap() {
        LocalDate from = LocalDate.of(2025, 1, 1), to = LocalDate.of(2025, 1, 31);
        when(imports.findCompletedOverlapping(from, to)).thenReturn(List.of());

        assertThat(service.bankBalanceForMonth(from, to)).isNull();
    }
}
