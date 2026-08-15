package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * A Square customer who is a salon owner/family member. Services rendered to them aren't charged, so
 * no Square order exists; the aggregator still credits the provider their commission on the menu price
 * for such bookings ("owner comp"). ({@code created_at} is DB-managed.)
 */
@Entity
@Table(name = "owner_customer")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class OwnerCustomer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false)
    private Long businessId;

    /** Square customer id — the join key against booking.customer_id. */
    @Column(name = "square_customer_id", nullable = false, unique = true)
    private String squareCustomerId;

    /** Display name shown in the admin list (best-effort; the customer id is the source of truth). */
    @Column(name = "label")
    private String label;

    @Column(name = "created_by")
    private String createdBy;
}
