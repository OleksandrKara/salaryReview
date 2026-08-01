package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Manually-entered business expense (materials/supplies today, other categories as they come up)
 * over an arbitrary date range — same shape as {@link AdSpendEntry}, but salon-wide rather than
 * landing-page-scoped, since a materials purchase isn't attributable to one marketing page. No
 * uniqueness constraint: a corrected re-entry is kept alongside the original rather than silently
 * overwriting it, so expense history stays auditable — see {@code ExpenseResolver}'s prorate-and-sum
 * resolution logic for how overlapping/multiple entries combine for any requested report period.
 */
@Entity
@Table(name = "expense_entries")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class ExpenseEntry {

    public static final String CATEGORY_MATERIALS = "MATERIALS";
    public static final String CATEGORY_RENT = "RENT";
    public static final String CATEGORY_UTILITIES = "UTILITIES";
    public static final String CATEGORY_OTHER = "OTHER";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "category", nullable = false)
    private String category;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(name = "amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(name = "note")
    private String note;

    @Column(name = "entered_by", length = 100)
    private String enteredBy;

    @Column(name = "entered_at", nullable = false)
    private Instant enteredAt;

    @PrePersist
    void touch() {
        if (enteredAt == null) enteredAt = Instant.now();
    }
}
