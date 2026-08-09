package com.salonreview.repo;

import com.salonreview.domain.BankTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface BankTransactionRepository extends JpaRepository<BankTransaction, Long> {

    /** Projection for {@link #findClosestMerchantByTrigram} — native-query column aliases
     * {@code merchant}/{@code sim} map to these getters by Spring Data's naming convention. */
    interface MerchantSimilarity {
        String getMerchant();
        Double getSim();
    }

    /** The single best {@code pg_trgm} trigram match, among merchants that already have at least
     * one active rule, for the fuzzy-similarity fallback tier (openspec design.md §16). Uses the
     * {@code %} operator (default 0.3 threshold) so the {@code idx_bank_transactions_merchant_trgm}
     * GIN index is actually usable; the caller applies the real 0.6 cutoff on the returned score. */
    @Query(value = """
            SELECT bt.normalized_merchant AS merchant, MAX(similarity(bt.merchant_key, :merchantKey)) AS sim
            FROM bank_transactions bt
            WHERE bt.merchant_key % :merchantKey
            AND EXISTS (
                SELECT 1 FROM merchant_rules mr
                WHERE mr.normalized_merchant = bt.normalized_merchant AND mr.active = true
            )
            GROUP BY bt.normalized_merchant
            ORDER BY sim DESC
            LIMIT 1
            """, nativeQuery = true)
    List<MerchantSimilarity> findClosestMerchantByTrigram(@Param("merchantKey") String merchantKey);

    List<BankTransaction> findByImportIdOrderByTransactionDateAsc(Long importId);

    /** Every transaction this import's completion linked to an {@code expense_entries} row — used
     * by revert (openspec design.md D10) to know exactly which rows to delete and which
     * transactions to reset. */
    List<BankTransaction> findByImportIdAndLinkedExpenseEntryIdIsNotNull(Long importId);

    /** The prior, non-reverted occurrence of this exact (fingerprint, occurrenceIndex) pair, if
     * any — cross-import duplicate detection (openspec design.md, expense-statement-import spec).
     * {@code BankStatementImport} isn't a JPA relation on {@code BankTransaction} (this codebase's
     * convention is plain FK columns, not entity graphs — see {@code StaffDocument}), so the join
     * is expressed directly in JPQL. */
    @Query("""
            SELECT t FROM BankTransaction t, BankStatementImport i
            WHERE t.importId = i.id
            AND i.status <> 'REVERTED'
            AND t.fingerprint = :fingerprint
            AND t.occurrenceIndex = :occurrenceIndex
            """)
    Optional<BankTransaction> findNonRevertedDuplicate(@Param("fingerprint") String fingerprint,
                                                        @Param("occurrenceIndex") int occurrenceIndex);

    /** The linked {@code expense_entries} ids created by any COMPLETED import whose transactions
     * fall in [from, to] — the statement-derived total for a given month (openspec design.md D11):
     * {@code OwnerOverviewService} sums exactly these entries instead of the generic
     * expense/manager-labor resolution once a month has any of them. */
    @Query("""
            SELECT t.linkedExpenseEntryId FROM BankTransaction t, BankStatementImport i
            WHERE t.importId = i.id
            AND i.status = 'COMPLETED'
            AND t.linkedExpenseEntryId IS NOT NULL
            AND t.transactionDate BETWEEN :from AND :to
            """)
    List<Long> findLinkedExpenseEntryIdsForCompletedImportsOverlapping(@Param("from") LocalDate from,
                                                                        @Param("to") LocalDate to);

    boolean existsByCategory(String category);
}
