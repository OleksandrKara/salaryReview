package com.salonreview.square;

import com.salonreview.domain.ExpenseEntry;
import com.salonreview.repo.ExpenseEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

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

    /** Resolves total expenses for [from, to] from the flexible {@code expense_entries} ledger —
     * see {@link ExpenseResolver}. Used by {@code OwnerOverviewService} to compute net revenue. */
    public BigDecimal resolveExpenseTotal(LocalDate from, LocalDate to) {
        return ExpenseResolver.resolve(repository.findOverlapping(from, to), from, to);
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
