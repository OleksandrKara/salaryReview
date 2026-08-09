package com.salonreview.square;

import com.salonreview.domain.ExpenseCategoryDefinition;
import com.salonreview.repo.BankTransactionRepository;
import com.salonreview.repo.ExpenseCategoryRepository;
import com.salonreview.repo.ExpenseEntryRepository;
import com.salonreview.repo.MerchantRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ExpenseCategoryServiceTest {

    private ExpenseCategoryRepository categories;
    private ExpenseEntryRepository expenseEntries;
    private BankTransactionRepository transactions;
    private MerchantRuleRepository rules;
    private ExpenseCategoryService service;

    @BeforeEach
    void setUp() {
        categories = mock(ExpenseCategoryRepository.class);
        expenseEntries = mock(ExpenseEntryRepository.class);
        transactions = mock(BankTransactionRepository.class);
        rules = mock(MerchantRuleRepository.class);
        service = new ExpenseCategoryService(categories, expenseEntries, transactions, rules);

        when(categories.findAllByOrderBySortOrderAscLabelAsc()).thenReturn(List.of());
        when(categories.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(expenseEntries.existsByCategory(anyString())).thenReturn(false);
        when(transactions.existsByCategory(anyString())).thenReturn(false);
        when(rules.existsByCategory(anyString())).thenReturn(false);
    }

    @Test
    @DisplayName("Creating a category derives an uppercase, underscore-joined code from the label")
    void createDerivesCode() {
        when(categories.existsByCode("GIFT_CARDS")).thenReturn(false);

        var result = service.create("Gift Cards");

        assertThat(result.getCode()).isEqualTo("GIFT_CARDS");
        assertThat(result.getLabel()).isEqualTo("Gift Cards");
        assertThat(result.isProtectedCategory()).isFalse();
    }

    @Test
    @DisplayName("Creating a category whose code already exists is rejected")
    void createRejectsDuplicateCode() {
        when(categories.existsByCode("RENT")).thenReturn(true);

        assertThatThrownBy(() -> service.create("Rent"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    @DisplayName("Creating a blank-labeled category is rejected")
    void createRejectsBlankLabel() {
        assertThatThrownBy(() -> service.create("   "))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("Renaming only touches the label, never the code")
    void renameOnlyTouchesLabel() {
        var existing = ExpenseCategoryDefinition.builder().id(1L).code("MATERIALS").label("Materials").build();
        when(categories.findById(1L)).thenReturn(Optional.of(existing));

        var result = service.rename(1L, "Supplies");

        assertThat(result.getLabel()).isEqualTo("Supplies");
        assertThat(result.getCode()).isEqualTo("MATERIALS");
    }

    @Test
    @DisplayName("A protected category can never be deleted")
    void deleteRejectsProtectedCategory() {
        var protectedCategory = ExpenseCategoryDefinition.builder().id(1L).code("MANAGER_TIME")
                .label("Manager time").protectedCategory(true).build();
        when(categories.findById(1L)).thenReturn(Optional.of(protectedCategory));

        assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("built-in");
        verify(categories, never()).delete(any());
    }

    @Test
    @DisplayName("A category already in use by an expense entry can't be deleted")
    void deleteRejectsInUseCategory() {
        var category = ExpenseCategoryDefinition.builder().id(2L).code("RENT").label("Rent")
                .protectedCategory(false).build();
        when(categories.findById(2L)).thenReturn(Optional.of(category));
        when(expenseEntries.existsByCategory("RENT")).thenReturn(true);

        assertThatThrownBy(() -> service.delete(2L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already used");
        verify(categories, never()).delete(any());
    }

    @Test
    @DisplayName("An unused, unprotected category can be deleted")
    void deleteAllowsUnusedCategory() {
        var category = ExpenseCategoryDefinition.builder().id(3L).code("GIFT_CARDS").label("Gift Cards")
                .protectedCategory(false).build();
        when(categories.findById(3L)).thenReturn(Optional.of(category));

        service.delete(3L);

        verify(categories).delete(category);
    }
}
