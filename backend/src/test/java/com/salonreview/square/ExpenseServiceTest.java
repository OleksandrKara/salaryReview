package com.salonreview.square;

import com.salonreview.domain.ExpenseEntry;
import com.salonreview.repo.ExpenseEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ExpenseServiceTest {

    private ExpenseEntryRepository repository;
    private ExpenseCategoryService categories;
    private ExpenseService service;

    @BeforeEach
    void setUp() {
        repository = mock(ExpenseEntryRepository.class);
        categories = mock(ExpenseCategoryService.class);
        // No personal categories by default — individual tests override to exercise the exclusion.
        when(categories.personalCategoryCodes()).thenReturn(Set.of());
        service = new ExpenseService(repository, categories);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("resolveExpenseTotal delegates to the resolver over the repository's overlapping entries")
    void resolveExpenseTotalDelegates() {
        LocalDate from = LocalDate.of(2026, 7, 1);
        LocalDate to = LocalDate.of(2026, 7, 31);
        when(repository.findOverlapping(from, to)).thenReturn(List.of(
                ExpenseEntry.builder().category("MATERIALS").periodStart(from).periodEnd(to)
                        .amount(new BigDecimal("200.00")).build()));

        assertThat(service.resolveExpenseTotal(from, to)).isEqualByComparingTo("200.00");
    }

    @Test
    @DisplayName("resolveExpenseTotal excludes MANAGER_TIME and PROVIDER_PAYROLL entries — those are separate figures")
    void resolveExpenseTotalExcludesManagerTime() {
        LocalDate from = LocalDate.of(2026, 7, 1);
        LocalDate to = LocalDate.of(2026, 7, 31);
        when(repository.findOverlapping(from, to)).thenReturn(List.of(
                ExpenseEntry.builder().category("MATERIALS").periodStart(from).periodEnd(to)
                        .amount(new BigDecimal("200.00")).build(),
                ExpenseEntry.builder().category("MANAGER_TIME").periodStart(from).periodEnd(to)
                        .amount(new BigDecimal("500.00")).build(),
                ExpenseEntry.builder().category("PROVIDER_PAYROLL").periodStart(from).periodEnd(to)
                        .amount(new BigDecimal("900.00")).build()));

        assertThat(service.resolveExpenseTotal(from, to)).isEqualByComparingTo("200.00");
    }

    @Test
    @DisplayName("resolveExpenseTotal excludes personal-flagged categories — they never reduce Net Profit")
    void resolveExpenseTotalExcludesPersonal() {
        LocalDate from = LocalDate.of(2026, 7, 1);
        LocalDate to = LocalDate.of(2026, 7, 31);
        when(categories.personalCategoryCodes()).thenReturn(Set.of("PERSONAL"));
        when(repository.findOverlapping(from, to)).thenReturn(List.of(
                ExpenseEntry.builder().category("MATERIALS").periodStart(from).periodEnd(to)
                        .amount(new BigDecimal("200.00")).build(),
                ExpenseEntry.builder().category("PERSONAL").periodStart(from).periodEnd(to)
                        .amount(new BigDecimal("2364.02")).build()));

        assertThat(service.resolveExpenseTotal(from, to)).isEqualByComparingTo("200.00");
    }

    @Test
    @DisplayName("resolveExpenseTotal includes owner-added custom categories — not just the original 4")
    void resolveExpenseTotalIncludesCustomCategories() {
        LocalDate from = LocalDate.of(2026, 7, 1);
        LocalDate to = LocalDate.of(2026, 7, 31);
        when(repository.findOverlapping(from, to)).thenReturn(List.of(
                ExpenseEntry.builder().category("MATERIALS").periodStart(from).periodEnd(to)
                        .amount(new BigDecimal("200.00")).build(),
                ExpenseEntry.builder().category("CONTRACTORS").periodStart(from).periodEnd(to)
                        .amount(new BigDecimal("350.00")).build()));

        assertThat(service.resolveExpenseTotal(from, to)).isEqualByComparingTo("550.00");
    }

    @Test
    @DisplayName("resolveManagerLaborManualTotal sums only MANAGER_TIME entries")
    void resolveManagerLaborManualTotalOnlyManagerTime() {
        LocalDate from = LocalDate.of(2026, 6, 1);
        LocalDate to = LocalDate.of(2026, 6, 30);
        when(repository.findOverlapping(from, to)).thenReturn(List.of(
                ExpenseEntry.builder().category("MATERIALS").periodStart(from).periodEnd(to)
                        .amount(new BigDecimal("200.00")).build(),
                ExpenseEntry.builder().category("MANAGER_TIME").periodStart(from).periodEnd(to)
                        .amount(new BigDecimal("500.00")).build()));

        assertThat(service.resolveManagerLaborManualTotal(from, to)).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("resolvePersonalTotal sums only personal-flagged categories")
    void resolvePersonalTotalSumsOnlyPersonal() {
        LocalDate from = LocalDate.of(2026, 7, 1);
        LocalDate to = LocalDate.of(2026, 7, 31);
        when(categories.personalCategoryCodes()).thenReturn(Set.of("PERSONAL"));
        when(repository.findOverlapping(from, to)).thenReturn(List.of(
                ExpenseEntry.builder().category("MATERIALS").periodStart(from).periodEnd(to)
                        .amount(new BigDecimal("200.00")).build(),
                ExpenseEntry.builder().category("PERSONAL").periodStart(from).periodEnd(to)
                        .amount(new BigDecimal("2364.02")).build()));

        assertThat(service.resolvePersonalTotal(from, to)).isEqualByComparingTo("2364.02");
    }

    @Test
    @DisplayName("resolveCashBusinessExpenseTotal sums only entries flagged paidInCash")
    void resolveCashBusinessExpenseTotalSumsOnlyCashPaid() {
        LocalDate from = LocalDate.of(2026, 7, 1);
        LocalDate to = LocalDate.of(2026, 7, 31);
        when(repository.findOverlapping(from, to)).thenReturn(List.of(
                ExpenseEntry.builder().category("MATERIALS").periodStart(from).periodEnd(to)
                        .amount(new BigDecimal("200.00")).paidInCash(false).build(),
                ExpenseEntry.builder().category("CONTRACTORS").periodStart(from).periodEnd(to)
                        .amount(new BigDecimal("150.00")).paidInCash(true).build()));

        assertThat(service.resolveCashBusinessExpenseTotal(from, to)).isEqualByComparingTo("150.00");
    }

    @Test
    @DisplayName("resolveStatementDerivedExpenseTotal sums only the generic-category entries among the given ids")
    void resolveStatementDerivedExpenseTotalSumsGenericOnly() {
        when(repository.findAllById(List.of(1L, 2L, 3L))).thenReturn(List.of(
                ExpenseEntry.builder().id(1L).category("MATERIALS").amount(new BigDecimal("120.00")).build(),
                ExpenseEntry.builder().id(2L).category("MANAGER_TIME").amount(new BigDecimal("60.00")).build(),
                ExpenseEntry.builder().id(3L).category("PROVIDER_PAYROLL").amount(new BigDecimal("400.00")).build()));

        assertThat(service.resolveStatementDerivedExpenseTotal(List.of(1L, 2L, 3L))).isEqualByComparingTo("120.00");
    }

    @Test
    @DisplayName("resolveStatementDerivedExpenseTotal excludes personal-flagged categories")
    void resolveStatementDerivedExpenseTotalExcludesPersonal() {
        when(categories.personalCategoryCodes()).thenReturn(Set.of("PERSONAL"));
        when(repository.findAllById(List.of(1L, 2L))).thenReturn(List.of(
                ExpenseEntry.builder().id(1L).category("MATERIALS").amount(new BigDecimal("120.00")).build(),
                ExpenseEntry.builder().id(2L).category("PERSONAL").amount(new BigDecimal("2364.02")).build()));

        assertThat(service.resolveStatementDerivedExpenseTotal(List.of(1L, 2L))).isEqualByComparingTo("120.00");
    }

    @Test
    @DisplayName("resolveStatementDerivedExpenseTotal includes owner-added custom categories")
    void resolveStatementDerivedExpenseTotalIncludesCustomCategories() {
        when(repository.findAllById(List.of(1L, 2L))).thenReturn(List.of(
                ExpenseEntry.builder().id(1L).category("MATERIALS").amount(new BigDecimal("120.00")).build(),
                ExpenseEntry.builder().id(2L).category("CONTRACTORS").amount(new BigDecimal("350.00")).build()));

        assertThat(service.resolveStatementDerivedExpenseTotal(List.of(1L, 2L))).isEqualByComparingTo("470.00");
    }

    @Test
    @DisplayName("resolveStatementDerivedManagerLaborTotal sums only the MANAGER_TIME entries among the given ids")
    void resolveStatementDerivedManagerLaborTotalSumsManagerTimeOnly() {
        when(repository.findAllById(List.of(1L, 2L))).thenReturn(List.of(
                ExpenseEntry.builder().id(1L).category("MATERIALS").amount(new BigDecimal("120.00")).build(),
                ExpenseEntry.builder().id(2L).category("MANAGER_TIME").amount(new BigDecimal("60.00")).build()));

        assertThat(service.resolveStatementDerivedManagerLaborTotal(List.of(1L, 2L))).isEqualByComparingTo("60.00");
    }

    @Test
    @DisplayName("resolveStatementDerivedProviderPayrollTotal sums only the PROVIDER_PAYROLL entries among the given ids")
    void resolveStatementDerivedProviderPayrollTotalSumsProviderPayrollOnly() {
        when(repository.findAllById(List.of(1L, 2L))).thenReturn(List.of(
                ExpenseEntry.builder().id(1L).category("MATERIALS").amount(new BigDecimal("120.00")).build(),
                ExpenseEntry.builder().id(2L).category("PROVIDER_PAYROLL").amount(new BigDecimal("400.00")).build()));

        assertThat(service.resolveStatementDerivedProviderPayrollTotal(List.of(1L, 2L))).isEqualByComparingTo("400.00");
    }

    @Test
    @DisplayName("resolveStatementDerivedPersonalTotal sums only personal-flagged categories among the given ids")
    void resolveStatementDerivedPersonalTotalSumsPersonalOnly() {
        when(categories.personalCategoryCodes()).thenReturn(Set.of("PERSONAL"));
        when(repository.findAllById(List.of(1L, 2L))).thenReturn(List.of(
                ExpenseEntry.builder().id(1L).category("MATERIALS").amount(new BigDecimal("120.00")).build(),
                ExpenseEntry.builder().id(2L).category("PERSONAL").amount(new BigDecimal("400.00")).build()));

        assertThat(service.resolveStatementDerivedPersonalTotal(List.of(1L, 2L))).isEqualByComparingTo("400.00");
    }

    @Test
    @DisplayName("Statement-derived totals are zero for an empty id list, without querying the repository")
    void statementDerivedTotalsEmptyIdsIsZero() {
        assertThat(service.resolveStatementDerivedExpenseTotal(List.of())).isEqualByComparingTo("0.00");
        assertThat(service.resolveStatementDerivedManagerLaborTotal(List.of())).isEqualByComparingTo("0.00");
        assertThat(service.resolveStatementDerivedProviderPayrollTotal(List.of())).isEqualByComparingTo("0.00");
        assertThat(service.resolveStatementDerivedPersonalTotal(List.of())).isEqualByComparingTo("0.00");
        verify(repository, never()).findAllById(any());
    }

    @Test
    @DisplayName("createExpenseEntry saves a new row with the amount scaled to 2 decimals")
    void createExpenseEntrySaves() {
        ExpenseEntry saved = service.createExpenseEntry("MATERIALS", LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31), new BigDecimal("199.999"), "OPI restock", "owner");

        assertThat(saved.getCategory()).isEqualTo("MATERIALS");
        assertThat(saved.getAmount()).isEqualByComparingTo("200.00");
        assertThat(saved.getNote()).isEqualTo("OPI restock");
        assertThat(saved.getEnteredBy()).isEqualTo("owner");
        assertThat(saved.isPaidInCash()).isFalse();
        verify(repository).save(any());
    }

    @Test
    @DisplayName("createExpenseEntry with paidInCash=true saves a cash-flagged row")
    void createExpenseEntrySavesCashFlag() {
        ExpenseEntry saved = service.createExpenseEntry("CONTRACTORS", LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31), new BigDecimal("150.00"), "Paid cash", "owner", true);

        assertThat(saved.isPaidInCash()).isTrue();
    }

    @Test
    @DisplayName("listExpenseEntries delegates to the repository's most-recent-first query")
    void listExpenseEntriesDelegates() {
        service.listExpenseEntries();
        verify(repository).findAllByOrderByPeriodStartDesc();
    }

    @Test
    @DisplayName("updateExpenseEntry edits an existing entry in place")
    void updateExpenseEntryEditsInPlace() {
        ExpenseEntry existing = ExpenseEntry.builder().id(1L).category("MATERIALS")
                .periodStart(LocalDate.of(2026, 7, 1)).periodEnd(LocalDate.of(2026, 7, 31))
                .amount(new BigDecimal("100.00")).build();
        when(repository.findById(1L)).thenReturn(Optional.of(existing));

        Optional<ExpenseEntry> result = service.updateExpenseEntry(1L, "RENT",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), new BigDecimal("150.00"), "corrected", true);

        assertThat(result).isPresent();
        assertThat(existing.getCategory()).isEqualTo("RENT");
        assertThat(existing.getAmount()).isEqualByComparingTo("150.00");
        assertThat(existing.getNote()).isEqualTo("corrected");
        assertThat(existing.isPaidInCash()).isTrue();
    }

    @Test
    @DisplayName("updateExpenseEntry on an unknown id is empty, doesn't error")
    void updateExpenseEntryUnknownIdIsEmpty() {
        when(repository.findById(999L)).thenReturn(Optional.empty());

        assertThat(service.updateExpenseEntry(999L, "MATERIALS", LocalDate.now(), LocalDate.now(),
                BigDecimal.TEN, null, false)).isEmpty();
    }

    @Test
    @DisplayName("deleteExpenseEntry removes an existing entry and returns true")
    void deleteExpenseEntryRemovesExisting() {
        when(repository.existsById(1L)).thenReturn(true);

        assertThat(service.deleteExpenseEntry(1L)).isTrue();
        verify(repository).deleteById(1L);
    }

    @Test
    @DisplayName("deleteExpenseEntry on an unknown id returns false, doesn't call deleteById")
    void deleteExpenseEntryUnknownIdReturnsFalse() {
        when(repository.existsById(999L)).thenReturn(false);

        assertThat(service.deleteExpenseEntry(999L)).isFalse();
        verify(repository, never()).deleteById(any());
    }
}
