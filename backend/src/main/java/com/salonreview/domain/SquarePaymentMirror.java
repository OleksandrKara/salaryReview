package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A local, raw copy of one Square Payment — see {@link SquareBookingMirror}'s own doc for the
 * mirror's rationale/lifecycle. Payroll was an explicit Phase 1 non-goal; since Phase 2f, {@code
 * SquareMonthAggregator#aggregateFromMirror} reads it (the shadow-diff twin of the still-live
 * {@code aggregate()}, ahead of the eventual cutover).
 */
@Entity
@Table(name = "square_payment")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class SquarePaymentMirror {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false)
    private Long businessId;

    @Column(name = "square_payment_id", nullable = false)
    private String squarePaymentId;

    @Column(name = "square_order_id")
    private String squareOrderId;

    /** Raw Square customer id — see {@link SquareBookingMirror#getSquareCustomerId()}'s own doc. */
    @Column(name = "square_customer_id")
    private String squareCustomerId;

    private String status;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "total_money")
    private BigDecimal totalMoney;

    @Column(name = "tip_money")
    private BigDecimal tipMoney;

    @Column(name = "synced_at", nullable = false)
    @Builder.Default
    private Instant syncedAt = Instant.now();
}
