package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One customer visit: a day a customer was served by a provider. The atomic fact behind retention
 * analytics — new/returning, cohort retention, and trend are all derived from these rows. Unique on
 * {@code (customerId, providerRef, serviceDate)} (multiple services the same day collapse to one
 * visit; visiting two providers in a day is two rows).
 */
@Entity
@Table(name = "provider_visit")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class ProviderVisit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false)
    private Long businessId;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    /** Aggregator's stable provider id (Square team member). */
    @Column(name = "provider_ref", nullable = false, length = 64)
    private String providerRef;

    /** Denormalized display name; latest ingest wins. */
    @Column(name = "provider_name")
    private String providerName;

    @Column(name = "service_date", nullable = false)
    private LocalDate serviceDate;

    @Column(name = "rebooked_same_day", nullable = false)
    @Builder.Default
    private boolean rebookedSameDay = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
