package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A learned categorization rule for a merchant, at one of four specificity tiers (see
 * {@code MerchantRuleEngine}, openspec design.md D4/D9). At most one active {@code MERCHANT}-tier
 * rule may exist per {@link #normalizedMerchant} (enforced by a partial unique index in V65) — a
 * second, conflicting plain-merchant categorization must be resolved by the owner (replace it, or
 * add a keyword/amount-range rule to disambiguate) rather than silently overwritten.
 */
@Entity
@Table(name = "merchant_rules")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class MerchantRule {

    public static final String TYPE_FINGERPRINT = "FINGERPRINT";
    public static final String TYPE_MERCHANT = "MERCHANT";
    public static final String TYPE_MERCHANT_KEYWORD = "MERCHANT_KEYWORD";
    public static final String TYPE_MERCHANT_AMOUNT_RANGE = "MERCHANT_AMOUNT_RANGE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_type", nullable = false)
    private String ruleType;

    @Column(name = "normalized_merchant", nullable = false)
    private String normalizedMerchant;

    /** MERCHANT_KEYWORD only. */
    @Column
    private String keyword;

    /** MERCHANT_AMOUNT_RANGE only. */
    @Column(name = "amount_min", precision = 10, scale = 2)
    private BigDecimal amountMin;

    @Column(name = "amount_max", precision = 10, scale = 2)
    private BigDecimal amountMax;

    /** FINGERPRINT only. */
    @Column
    private String fingerprint;

    /** An {@link ExpenseEntry} category value, or an {@code EXCLUDE_<reason>} pseudo-value
     * (see {@link BankTransaction#getExcludedReason()}, openspec design.md D8). */
    @Column(nullable = false)
    private String category;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "times_applied", nullable = false)
    @Builder.Default
    private int timesApplied = 0;

    @Column(name = "last_applied_at")
    private Instant lastAppliedAt;

    /** Traceability: the transaction whose review decision created this rule. */
    @Column(name = "source_transaction_id")
    private Long sourceTransactionId;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
