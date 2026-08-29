package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * A local, raw copy of one Square Order — see {@link SquareBookingMirror}'s own doc for the
 * mirror's rationale/lifecycle. Read by {@code MarketingBookingPaymentMatcher} and, since Phase 2,
 * by {@code SquareMonthAggregator}'s mirror-backed path — {@code discounts}/{@code
 * LineItem#appliedDiscounts} exist specifically for the aggregator's discount-coverage policy, and
 * {@code LineItem#name} for its cancellation-fee detection; neither is used by the marketing path.
 */
@Entity
@Table(name = "square_order")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class SquareOrderMirror {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false)
    private Long businessId;

    @Column(name = "square_order_id", nullable = false)
    private String squareOrderId;

    /** Raw Square customer id — see {@link SquareBookingMirror#getSquareCustomerId()}'s own doc on
     * why callers must canonicalize themselves. */
    @Column(name = "square_customer_id")
    private String squareCustomerId;

    private String state;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "total_tip_money")
    private BigDecimal totalTipMoney;

    @Column(name = "total_discount_money")
    private BigDecimal totalDiscountMoney;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tenders_json", columnDefinition = "jsonb")
    private List<Tender> tenders;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "line_items_json", columnDefinition = "jsonb")
    private List<LineItem> lineItems;

    /** Order-level discounts (e.g. a "Deposit" or promo discount) — see {@link OrderDiscount}'s own
     * doc. Null/empty for orders synced before this field existed (see V135); the aggregator's
     * discount-coverage policy treats a missing discount the same as a genuinely-absent one. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "discounts_json", columnDefinition = "jsonb")
    private List<OrderDiscount> discounts;

    @Column(name = "synced_at", nullable = false)
    @Builder.Default
    private Instant syncedAt = Instant.now();

    public record Tender(String type, BigDecimal amount) {}

    public record LineItem(String catalogObjectId, String name, BigDecimal grossSalesMoney,
                           BigDecimal totalMoney, BigDecimal totalDiscountMoney,
                           List<AppliedDiscount> appliedDiscounts) {}

    /** One order-level discount definition — {@code name} is what both {@code PrepaidService} (its
     * own "Deposit" match) and, since Phase 2, {@code SquareMonthAggregator}'s discount-coverage
     * policy match against {@code SalonConfig#coveredDiscountNameSubstrings}. */
    public record OrderDiscount(String uid, String name, BigDecimal appliedMoney) {}

    /** Links a line item back to the specific {@link OrderDiscount} (by {@code discountUid}) that
     * reduced it, and by how much — needed to prorate an order-level discount back to one line. */
    public record AppliedDiscount(String uid, String discountUid, BigDecimal appliedMoney) {}
}
