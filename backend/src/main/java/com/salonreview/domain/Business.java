package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * The tenant root — see openspec/changes/multi-tenant-salon-platform/design.md D1. Every
 * business-owned table carries a business_id (direct column or inherited via FK); this is the row
 * that id points at. The existing salon (short_code "akluxnails") is Business A, backfilled by V84.
 */
@Entity
@Table(name = "business")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Business {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "short_code", nullable = false, unique = true)
    private String shortCode;

    @Column(nullable = false)
    private String timezone;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
