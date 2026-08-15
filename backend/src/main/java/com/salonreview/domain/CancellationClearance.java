package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * "I checked the cameras for this cancelled appointment and no procedure was done" stamp. Detection
 * is live (see {@code CancelledAppointmentService}); this table just records that an owner has
 * reviewed a specific seller-cancelled booking so it stops showing on the per-period warning badge.
 * Delete the row to un-clear. Deliberately separate from {@link SuspiciousBookingClearance} so the
 * two review flows never interfere.
 */
@Entity
@Table(name = "cancellation_clearance")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class CancellationClearance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false)
    private Long businessId;

    @Column(name = "square_booking_id", nullable = false, unique = true)
    private String squareBookingId;

    @Column(name = "cleared_by_username", nullable = false, length = 100)
    private String clearedByUsername;

    @Column(name = "cleared_at", nullable = false)
    private Instant clearedAt;

    @Column(length = 255)
    private String note;

    @PrePersist
    void prePersist() {
        if (clearedAt == null) clearedAt = Instant.now();
    }
}
