package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A prepaid package: one Square invoice paid in advance for {@link #totalServices} services with a
 * single {@link #providerId}, drawn down over later visits (see {@code PrepaidRedemption}). The
 * provider is paid per draw-down on the service's catalog price; this row is the balance + proof.
 * ({@code created_at} is DB-managed.)
 */
@Entity
@Table(name = "prepaid_package")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class PrepaidPackage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Square customer id, when known (used to find candidate bookings). */
    @Column(name = "customer_id")
    private String customerId;

    @Column(name = "customer_name", nullable = false)
    private String customerName;

    @Column(name = "provider_id", nullable = false)
    private Long providerId;

    @Column(name = "paid_date", nullable = false)
    private LocalDate paidDate;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "total_services", nullable = false)
    private int totalServices;

    /** Optional Square invoice number, for reference. */
    @Column(name = "invoice_ref")
    private String invoiceRef;

    @Column(name = "created_by")
    private String createdBy;
}
