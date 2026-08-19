package com.salonreview.domain;

import com.salonreview.commission.CommissionConfig;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "salon_config")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class SalonConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "business_id", nullable = false, unique = true)
    private Long businessId;

    @Column(name = "owner_short_name", nullable = false)
    private String ownerShortName;

    @Column(name = "tier_service_threshold", nullable = false)
    private int tierServiceThreshold;

    @Column(name = "service_price_cutoff", nullable = false, precision = 10, scale = 2)
    private BigDecimal servicePriceCutoff;

    @Column(name = "base_commission_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal baseCommissionRate;

    @Column(name = "tier_commission_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal tierCommissionRate;

    @Column(name = "card_tip_fee_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal cardTipFeeRate;

    /** See {@link CommissionConfig#tierEnabled()}'s own doc — false means flat baseCommissionRate,
     * no exceptions, for businesses that don't run the tier program at all. */
    @Column(name = "tier_enabled", nullable = false)
    private boolean tierEnabled;

    /** Phase 4.4: the no-show/late-cancellation fee this business charges, or null when the feature
     * is off entirely (no fee program) — see {@code NoShowFeeService}'s own doc for what null does
     * at every call site. Business A keeps its historical $25; a null value is a real, deliberate
     * choice, not an oversight, so nothing defaults it to a nonzero amount. */
    @Column(name = "no_show_fee_amount", precision = 10, scale = 2)
    private BigDecimal noShowFeeAmount;

    /** false (the historical, still-default behavior): every Square order discount is "absorbed" by
     * the salon — providers are paid commission on the full pre-discount menu price regardless of
     * what was actually collected. true: only discounts whose name matches {@link
     * #coveredDiscountNames} are absorbed; every other discount reduces the provider's commission
     * basis down to what was actually collected. See {@code SquareMonthAggregator}'s own doc for
     * where this is applied. Deliberately false-means-legacy (not true-means-legacy): a {@code
     * boolean} field's own Java default, and Mockito's default for an unstubbed mock method, are
     * both {@code false} — so any existing or future test/code path that never sets this still
     * behaves exactly like it always has. Found live 2026-08-19: a business wanted ordinary promo
     * discounts to come out of the salon's own margin (not the provider's pay) while still paying
     * providers in full on prepaid-deposit discounts specifically. */
    @Column(name = "restrict_discount_coverage", nullable = false)
    private boolean restrictDiscountCoverage;

    /** Comma-separated, case-insensitive substrings matched against each Square discount's own name
     * (e.g. {@code "deposit"} matches the real discount name {@code "Deposit "}) — only consulted
     * when {@link #restrictDiscountCoverage} is true. Same free-text-list convention as {@code
     * PrepaidPackage#invoiceRef}. Null/blank with restrictDiscountCoverage true means no discount is
     * covered at all — every discount reduces the commission basis. */
    @Column(name = "covered_discount_names", length = 500)
    private String coveredDiscountNames;

    /** {@link #coveredDiscountNames} split, trimmed, lower-cased, and emptied of blanks — ready to
     * match against a lower-cased discount name via {@code String#contains}. */
    public java.util.Set<String> coveredDiscountNameSubstrings() {
        if (coveredDiscountNames == null || coveredDiscountNames.isBlank()) return java.util.Set.of();
        return java.util.Arrays.stream(coveredDiscountNames.split(","))
                .map(String::trim).map(s -> s.toLowerCase(java.util.Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toSet());
    }

    /** Build the commission engine's config from the stored settings. */
    public CommissionConfig toCommissionConfig() {
        return new CommissionConfig(tierServiceThreshold, baseCommissionRate, tierCommissionRate, cardTipFeeRate,
                tierEnabled);
    }
}
