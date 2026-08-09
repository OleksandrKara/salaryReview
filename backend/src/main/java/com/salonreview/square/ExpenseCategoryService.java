package com.salonreview.square;

import com.salonreview.domain.ExpenseCategoryDefinition;
import com.salonreview.repo.BankTransactionRepository;
import com.salonreview.repo.ExpenseCategoryRepository;
import com.salonreview.repo.ExpenseEntryRepository;
import com.salonreview.repo.MerchantRuleRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * CRUD for owner-editable expense categories. {@code code} is generated once from the label at
 * creation time and never changes afterward (it's what's actually stored on
 * ExpenseEntry/BankTransaction/MerchantRule rows) — renaming only ever touches {@code label}.
 * MANAGER_TIME and PROVIDER_PAYROLL are seeded as protected (V73) since their codes are hardcoded
 * backend constants with special net-revenue behavior; deleting either, or any category already in
 * use somewhere, is rejected rather than silently orphaning existing rows.
 */
@Service
public class ExpenseCategoryService {

    private static final Pattern NON_ALNUM = Pattern.compile("[^A-Z0-9]+");

    private final ExpenseCategoryRepository categories;
    private final ExpenseEntryRepository expenseEntries;
    private final BankTransactionRepository transactions;
    private final MerchantRuleRepository rules;

    public ExpenseCategoryService(ExpenseCategoryRepository categories, ExpenseEntryRepository expenseEntries,
                                   BankTransactionRepository transactions, MerchantRuleRepository rules) {
        this.categories = categories;
        this.expenseEntries = expenseEntries;
        this.transactions = transactions;
        this.rules = rules;
    }

    public List<ExpenseCategoryDefinition> list() {
        return categories.findAllByOrderBySortOrderAscLabelAsc();
    }

    /** Throws 400 if {@code code} isn't a currently-valid category — used by
     * {@code ExpenseController}'s manual-entry endpoints to reject a typo'd/unknown category up
     * front with a friendly message, instead of it hitting a raw DB error (or, now that
     * expense_entries has no category constraint, silently succeeding with a value that never
     * shows up in any Net-revenue total). Not used by the statement-reconciliation path
     * ({@code ExpenseImportService.completeReconciliation}), which trusts
     * {@code BankTransaction.category} — already constrained to this same list at review time. */
    public void assertValidCode(String code) {
        if (code == null || !categories.existsByCode(code)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    code == null ? "Category is required" : "'" + code + "' isn't a known expense category");
        }
    }

    @Transactional
    public ExpenseCategoryDefinition create(String label) {
        String trimmedLabel = label == null ? "" : label.trim();
        if (trimmedLabel.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Category name is required");
        }
        String code = codeFrom(trimmedLabel);
        if (categories.existsByCode(code)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A category matching '" + trimmedLabel + "' already exists");
        }
        int nextSort = categories.findAllByOrderBySortOrderAscLabelAsc().stream()
                .mapToInt(ExpenseCategoryDefinition::getSortOrder).max().orElse(0) + 10;
        return categories.save(ExpenseCategoryDefinition.builder()
                .code(code).label(trimmedLabel).protectedCategory(false).sortOrder(nextSort).build());
    }

    @Transactional
    public ExpenseCategoryDefinition rename(Long id, String label) {
        String trimmedLabel = label == null ? "" : label.trim();
        if (trimmedLabel.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Category name is required");
        }
        ExpenseCategoryDefinition c = categories.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such category"));
        c.setLabel(trimmedLabel);
        return categories.save(c);
    }

    @Transactional
    public void delete(Long id) {
        ExpenseCategoryDefinition c = categories.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such category"));
        if (c.isProtectedCategory()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "'" + c.getLabel() + "' is a built-in category and can't be deleted");
        }
        boolean inUse = expenseEntries.existsByCategory(c.getCode())
                || transactions.existsByCategory(c.getCode())
                || rules.existsByCategory(c.getCode());
        if (inUse) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "'" + c.getLabel() + "' is already used by an expense entry, transaction, or rule — " +
                            "reassign those first, or leave the category in place");
        }
        categories.delete(c);
    }

    /** Uppercase, non-alphanumeric runs collapsed to a single underscore, trimmed of leading/
     * trailing underscores — {@code "Gift Cards"} → {@code "GIFT_CARDS"}. */
    private static String codeFrom(String label) {
        String upper = label.toUpperCase(Locale.US);
        String code = NON_ALNUM.matcher(upper).replaceAll("_");
        code = code.replaceAll("^_+|_+$", "");
        if (code.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Category name must contain at least one letter or number");
        }
        return code;
    }
}
