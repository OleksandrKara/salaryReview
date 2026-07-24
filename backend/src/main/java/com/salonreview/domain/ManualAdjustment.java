package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A manual settlement adjustment — an owner/manager corrects a provider's pay for something Square
 * can't reflect on its own. Two directions: {@code gross > 0} credits a service Square recorded too
 * messily to auto-attribute (e.g. paid on a card machine as a custom amount); {@code gross < 0}
 * deducts a provider's commission for a service later refunded to the customer (or a similar
 * correction). Folded into the settlement like a card service: {@link #gross} is the commission
 * basis (signed), {@link #discount} is salon-absorbed (credits only — always 0 for a deduction),
 * {@link #tip} pays out (credits only — always 0 for a deduction). ({@code created_at} is
 * DB-managed.)
 */
@Entity
@Table(name = "manual_adjustment")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class ManualAdjustment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "provider_id", nullable = false)
    private Long providerId;

    @Column(name = "service_date", nullable = false)
    private LocalDate serviceDate;

    @Column(nullable = false)
    private BigDecimal gross;

    @Column(nullable = false)
    private BigDecimal discount;

    @Column(nullable = false)
    private BigDecimal tip;

    @Column(name = "service_name")
    private String serviceName;

    @Column(name = "created_by")
    private String createdBy;
}
