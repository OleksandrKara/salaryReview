package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * A manager/owner's manual award of the 50/50 tier to a provider for a specific calendar month,
 * overriding the automatic service-count decision. Absence of a row means "decide automatically".
 * ({@code created_at} is DB-managed and intentionally not mapped.)
 */
@Entity
@Table(name = "tier_grant")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class TierGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "provider_id", nullable = false)
    private Long providerId;

    @Column(nullable = false)
    private int year;

    @Column(nullable = false)
    private int month;
}
