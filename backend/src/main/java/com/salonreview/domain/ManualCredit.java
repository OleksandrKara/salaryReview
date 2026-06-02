package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A manual service credit — an owner/manager credits a provider for a service Square recorded too
 * messily to auto-attribute. Folded into the settlement like a card service: {@link #gross} is the
 * commission basis, {@link #discount} is salon-absorbed (shown), {@link #tip} pays out after the fee.
 * ({@code created_at} is DB-managed.)
 */
@Entity
@Table(name = "manual_credit")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class ManualCredit {

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
