package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Owner-configurable discount amount/minimum-spend for one business's signed REBOOK10/WINBACK5
 * promo link, plus the Square Catalog/CustomerGroup object ids backing it once created — see
 * {@code com.salonreview.sms.PromoConfigService}. Absence of a row for Business A (short_code
 * {@code akluxnails}) is expected — it keeps running on the legacy env-based
 * {@code RebookingProperties} config; this table only ever backs a business onboarded after it
 * shipped. Absence of a row for any other business simply means that business hasn't set up this
 * promo yet — same "not configured" convention every other optional automation config uses.
 */
@Entity
@Table(name = "business_promo_config")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class BusinessPromoConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false)
    private Long businessId;

    /** {@code REBOOK10} (same_day_rebooking_discount) or {@code WINBACK5} (lapsed_customer_winback,
     * also reused by repeat_customer_winback — see RebookingPromoSigner's own doc on why the two
     * winback automations deliberately share one promo/discount). */
    @Column(name = "promo_code", nullable = false)
    private String promoCode;

    @Column(name = "discount_cents", nullable = false)
    private Integer discountCents;

    /** Null = no minimum order subtotal required. */
    @Column(name = "min_spend_cents")
    private Integer minSpendCents;

    /** Null until the first save for this business/promoCode actually creates the Square objects. */
    @Column(name = "square_customer_group_id")
    private String squareCustomerGroupId;

    @Column(name = "square_discount_catalog_id")
    private String squareDiscountCatalogId;

    @Column(name = "square_pricing_rule_catalog_id")
    private String squarePricingRuleCatalogId;

    /** Shared across both promo codes for the same business — one "all products" product set is
     * enough for every pricing rule this business ever needs. */
    @Column(name = "square_product_set_catalog_id")
    private String squareProductSetCatalogId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private String updatedBy;

    public boolean squareConfigured() {
        return squareCustomerGroupId != null && squareDiscountCatalogId != null && squarePricingRuleCatalogId != null;
    }

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
