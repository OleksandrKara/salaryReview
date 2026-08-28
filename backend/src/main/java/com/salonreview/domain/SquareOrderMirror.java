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
 * mirror's rationale/lifecycle. Read only by {@code MarketingBookingPaymentMatcher}; never by
 * {@code SquareMonthAggregator} or any payroll path.
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

    @Column(name = "synced_at", nullable = false)
    @Builder.Default
    private Instant syncedAt = Instant.now();

    public record Tender(String type, BigDecimal amount) {}

    public record LineItem(String catalogObjectId, BigDecimal grossSalesMoney, BigDecimal totalMoney,
                           BigDecimal totalDiscountMoney) {}
}
