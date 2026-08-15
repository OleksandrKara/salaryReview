package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * A login's membership in one business, with the role it holds there — see
 * openspec/changes/multi-tenant-salon-platform/design.md D3. Every {@link AppUser} today has exactly
 * one row (backfilled into Business A by V85); the schema allows more without a working switcher UI
 * yet (design.md D3/D12) — {@code JpaUserDetailsService} fails loudly rather than guessing if a user
 * ever has zero or more than one. {@code role} is backfilled from {@code app_user.role} but not yet
 * authoritative — {@link AppUser#getRole()} is still what {@code AppUserPrincipal} reads; moving the
 * source of truth here is deliberately a separate, later change (design.md D3/D4).
 */
@Entity
@Table(name = "business_membership")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class BusinessMembership {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false)
    private Long businessId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;
}
