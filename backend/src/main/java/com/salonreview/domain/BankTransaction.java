package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A single parsed row from a {@link BankStatementImport}. {@code fingerprint} +
 * {@code occurrenceIndex} together identify a real-world transaction across separate imports (see
 * openspec design.md D7) — used both for same-import same-day-repeat disambiguation and for
 * cross-import duplicate detection. {@code status} tracks this row's place in the reconciliation
 * workflow; {@code linkedExpenseEntryId} is set only once this row has produced an
 * {@code expense_entries} row (on completion), and cleared again on revert (D10).
 */
@Entity
@Table(name = "bank_transactions")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class BankTransaction {

    public static final String STATUS_UNMATCHED = "UNMATCHED";
    public static final String STATUS_AUTO_MATCHED = "AUTO_MATCHED";
    public static final String STATUS_NEEDS_REVIEW = "NEEDS_REVIEW";
    public static final String STATUS_REVIEWED = "REVIEWED";
    public static final String STATUS_EXCLUDED = "EXCLUDED";
    public static final String STATUS_DUPLICATE = "DUPLICATE";

    public static final String EXCLUDE_TRANSFER = "TRANSFER";
    public static final String EXCLUDE_CREDIT_CARD_PAYMENT = "CREDIT_CARD_PAYMENT";
    public static final String EXCLUDE_PAYROLL = "PAYROLL";
    public static final String EXCLUDE_TAX = "TAX";
    public static final String EXCLUDE_OWNER_CONTRIBUTION = "OWNER_CONTRIBUTION";
    public static final String EXCLUDE_CASH_WITHDRAWAL = "CASH_WITHDRAWAL";
    public static final String EXCLUDE_REFUND = "REFUND";
    public static final String EXCLUDE_OTHER = "OTHER";
    /** A positive-amount row (money in) — never a real expense by definition, auto-excluded on
     * import before it ever reaches the rule engine (see ExpenseImportService). */
    public static final String EXCLUDE_DEPOSIT = "DEPOSIT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "import_id", nullable = false)
    private Long importId;

    @Column(name = "transaction_date", nullable = false)
    private LocalDate transactionDate;

    @Column(name = "raw_description", nullable = false)
    private String rawDescription;

    @Column(name = "normalized_merchant", nullable = false)
    private String normalizedMerchant;

    @Column(name = "merchant_key", nullable = false)
    private String merchantKey;

    /** Signed; negative = money out. */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private String fingerprint;

    @Column(name = "occurrence_index", nullable = false)
    @Builder.Default
    private int occurrenceIndex = 0;

    @Column(nullable = false)
    @Builder.Default
    private String status = STATUS_UNMATCHED;

    @Column(name = "matched_rule_id")
    private Long matchedRuleId;

    @Column(name = "match_reason")
    private String matchReason;

    /** 0.00-1.00, null when Unknown. */
    @Column(precision = 3, scale = 2)
    private BigDecimal confidence;

    /** Set once AUTO_MATCHED or REVIEWED. */
    @Column
    private String category;

    @Column(name = "excluded_reason")
    private String excludedReason;

    @Column(name = "linked_expense_entry_id")
    private Long linkedExpenseEntryId;

    @Column(name = "duplicate_of_transaction_id")
    private Long duplicateOfTransactionId;

    @Column(name = "reviewed_by", length = 100)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;
}
