package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * An owner-editable expense category (openspec change expense-import-reconciliation follow-up) —
 * {@link #code} is the stable value stored on {@code ExpenseEntry}/{@code BankTransaction}/
 * {@code MerchantRule}, {@link #label} is the display name shown in pickers. {@code protected}
 * categories (MANAGER_TIME, PROVIDER_PAYROLL — see {@link ExpenseEntry}'s own CATEGORY_ constants)
 * carry hardcoded backend behavior keyed on their code, so their code may never change and the row
 * itself may never be deleted; their label can still be freely renamed.
 */
@Entity
@Table(name = "expense_categories")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class ExpenseCategoryDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String label;

    @Column(name = "protected", nullable = false)
    @Builder.Default
    private boolean protectedCategory = false;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private int sortOrder = 0;

    /** Excludes this category from Net Profit's business-expense total — the owner uses it for
     * genuinely personal spending run through the business account (see design.md's P&L redesign).
     * Reported separately (Personal Bank Transactions), never subtracted from Net Profit. */
    @Column(name = "is_personal", nullable = false)
    @Builder.Default
    private boolean personal = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void touch() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
