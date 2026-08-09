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
    /** Merchant-agnostic: matches purely on one or more required substrings (all must be present)
     * in the raw description, stored newline-joined in {@link #keyword}. See {@code
     * MerchantRuleEngine} — needed because some bank descriptors embed a per-transaction reference
     * number with no separator, making normalized_merchant unique per transaction and useless as a
     * lookup key for this tier. */
    public static final String TYPE_KEYWORD = "KEYWORD";

    /** Delimiter joining the one or more required substrings stored in {@link #keyword} for a
     * {@link #TYPE_KEYWORD} rule — a literal newline can't appear in a bank descriptor or an
     * owner-typed keyword phrase, so it's a safe, simple choice. */
    public static final String KEYWORD_DELIMITER = "\n";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_type", nullable = false)
    private String ruleType;

    /** Null only for {@link #TYPE_KEYWORD} — every other tier is scoped to a specific merchant. */
    @Column(name = "normalized_merchant")
    private String normalizedMerchant;

    /** MERCHANT_KEYWORD: a single required substring. KEYWORD: one or more required substrings
     * joined by {@link #KEYWORD_DELIMITER} (all must be present — AND semantics). */
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
