package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A local, raw copy of one Square Customer — backfilled and kept fresh via webhook + a periodic
 * full re-sync (see {@code SquareCustomerMirrorIngestService}), so phone->customerId and
 * id->name resolution (previously one live {@code SquareClient#customerIdsForPhone}/
 * {@code canonicalCustomerIds}/{@code customerNames} call per contact) can query this table
 * instead. See the Phase 3 plan for the full rationale.
 */
@Entity
@Table(name = "square_customer")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class SquareCustomerMirror {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false)
    private Long businessId;

    @Column(name = "square_customer_id", nullable = false)
    private String squareCustomerId;

    /** Normalized E.164, matching {@code SquareClient#normalizePhone} — a lookup must normalize
     * its query the same way, or it will never match a stored row. */
    @Column(name = "phone_number")
    private String phoneNumber;

    @Column(name = "given_name")
    private String givenName;

    @Column(name = "family_name")
    private String familyName;

    @Column(name = "email_address")
    private String emailAddress;

    @Column(name = "square_created_at")
    private Instant squareCreatedAt;

    @Column(name = "synced_at", nullable = false)
    @Builder.Default
    private Instant syncedAt = Instant.now();
}
