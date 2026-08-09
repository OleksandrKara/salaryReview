package com.salonreview.square;

import com.salonreview.domain.ExpenseEntry;
import com.salonreview.repo.ExpenseEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Salon-wide business expenses (materials/supplies today, other categories as they come up) — the
 * cost side of "gross revenue" vs. "net revenue" on the Overview dashboard. Same flexible
 * arbitrary-period ledger shape as ad spend (see {@code MarketingAnalyticsService}'s own ad-spend
 * CRUD methods, which this mirrors), but salon-wide rather than landing-page-scoped.
 */
@Service
public class ExpenseService {

    private final ExpenseEntryRepository repository;

    public ExpenseService(ExpenseEntryRepository repository) {
        this.repository = repository;
    }

    /** "Generic" = every category except MANAGER_TIME and PROVIDER_PAYROLL, the only two with
     * dedicated resolvers ({@link #resolveManagerLaborManualTotal},
     * {@link #resolveStatementDerivedManagerLaborTotal}/{@link #resolveStatementDerivedProviderPayrollTotal}).
     * Exclusion-based rather than a hardcoded allowlist so any category the owner adds via the
     * category picker (see {@code ExpenseCategoryService}) is automatically generic with no code
     * change here, and a category later deleted from that picker still counts correctly against
     * historical entries that reference it. */
    private static boolean isGenericCategory(String category) {
        return category != null
                && !ExpenseEntry.CATEGORY_MANAGER_TIME.equals(category)
                && !ExpenseEntry.CATEGORY_PROVIDER_PAYROLL.equals(category);
    }

    /** Resolves total expenses for [from, to] from the flexible {@code expense_entries} ledger —
     * see {@link ExpenseResolver}. Used by {@code OwnerOverviewService} to compute net revenue.
     * Excludes MANAGER_TIME entries (see {@link #resolveManagerLaborManualTotal}). */
    public BigDecimal resolveExpenseTotal(LocalDate from, LocalDate to) {
        List<ExpenseEntry> generic = repository.findOverlapping(from, to).stream()
                .filter(e -> isGenericCategory(e.getCategory()))
                .toList();
        return ExpenseResolver.resolve(generic, from, to);
    }

    /** Resolves the manually-entered manager-labor backfill for [from, to] — only meaningful for
     * months before real clocked data exists (see {@code ManagerTimeService.totalLaborCost}, which
     * {@code OwnerOverviewService} prefers whenever it has any data for the month). */
    public BigDecimal resolveManagerLaborManualTotal(LocalDate from, LocalDate to) {
        List<ExpenseEntry> managerTime = repository.findOverlapping(from, to).stream()
                .filter(e -> ExpenseEntry.CATEGORY_MANAGER_TIME.equals(e.getCategory()))
                .toList();
        return ExpenseResolver.resolve(managerTime, from, to);
    }

    /** Sums exactly the given {@code expense_entries} ids that carry a generic category — used by
     * {@code OwnerOverviewService} for a statement-covered month (openspec design.md D11), where
     * the reconciled import's own linked entries are the *only* source, not the arbitrary-period
     * proration this class otherwise uses. Each entry is single-day (see design.md D3), so a plain
     * sum is exactly correct here — no proration needed. */
    public BigDecimal resolveStatementDerivedExpenseTotal(Collection<Long> linkedExpenseEntryIds) {
        return sumByCategories(linkedExpenseEntryIds, ExpenseService::isGenericCategory);
    }

    /** Same as {@link #resolveStatementDerivedExpenseTotal} but for the MANAGER_TIME category —
     * the statement-covered month's manager labor cost (design.md D11). */
    public BigDecimal resolveStatementDerivedManagerLaborTotal(Collection<Long> linkedExpenseEntryIds) {
        return sumByCategories(linkedExpenseEntryIds, ExpenseEntry.CATEGORY_MANAGER_TIME::equals);
    }

    /** Same as {@link #resolveStatementDerivedExpenseTotal} but for the PROVIDER_PAYROLL category —
     * the statement-covered month's real provider-commission payout, replacing the formula-computed
     * one entirely for that month (design.md D12). */
    public BigDecimal resolveStatementDerivedProviderPayrollTotal(Collection<Long> linkedExpenseEntryIds) {
        return sumByCategories(linkedExpenseEntryIds, ExpenseEntry.CATEGORY_PROVIDER_PAYROLL::equals);
    }

    private BigDecimal sumByCategories(Collection<Long> ids, Predicate<String> categoryMatch) {
        if (ids.isEmpty()) return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = repository.findAllById(ids).stream()
                .filter(e -> categoryMatch.test(e.getCategory()))
                .map(ExpenseEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    /** Records a new expense entry — never upserts; a corrected re-entry is kept alongside the
     * original so expense history stays auditable (see {@link ExpenseResolver}'s handling of
     * overlapping entries). */
    @Transactional
    public ExpenseEntry createExpenseEntry(String category, LocalDate periodStart, LocalDate periodEnd,
                                            BigDecimal amount, String note, String enteredBy) {
        return repository.save(ExpenseEntry.builder()
                .category(category)
                .periodStart(periodStart)
                .periodEnd(periodEnd)
                .amount(amount.setScale(2, RoundingMode.HALF_UP))
                .note(note)
                .enteredBy(enteredBy)
                .build());
    }

    /** Every entered expense row, most recent period first — for the expense-entry management UI
     * (a simple list, not a report). */
    public List<ExpenseEntry> listExpenseEntries() {
        return repository.findAllByOrderByPeriodStartDesc();
    }

    /** Edits an existing entry in place — for fixing an outright mistake (wrong amount/dates/
     * category), not a genuine revision (enter a new row via {@link #createExpenseEntry} for that,
     * so history stays auditable). Empty if the id doesn't exist, mirroring
     * {@link #deleteExpenseEntry}'s not-found handling. */
    @Transactional
    public Optional<ExpenseEntry> updateExpenseEntry(Long id, String category, LocalDate periodStart,
                                                      LocalDate periodEnd, BigDecimal amount, String note) {
        return repository.findById(id).map(entry -> {
            entry.setCategory(category);
            entry.setPeriodStart(periodStart);
            entry.setPeriodEnd(periodEnd);
            entry.setAmount(amount.setScale(2, RoundingMode.HALF_UP));
            entry.setNote(note);
            return repository.save(entry);
        });
    }

    /** Removes an outright mistaken entry — false if the id doesn't exist, so the controller can
     * 404 rather than silently no-op. */
    @Transactional
    public boolean deleteExpenseEntry(Long id) {
        if (!repository.existsById(id)) return false;
        repository.deleteById(id);
        return true;
    }
}
