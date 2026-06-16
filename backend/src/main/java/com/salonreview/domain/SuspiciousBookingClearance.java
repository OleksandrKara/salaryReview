package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * "I looked at this booking and it's fine" stamp. Detection is live (see {@code
 * SuspiciousBookingService}); this table just records that an owner/manager has acknowledged a
 * specific booking so it stops showing up on the per-period badge. Delete the row to un-clear.
 */
@Entity
@Table(name = "suspicious_booking_clearance")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class SuspiciousBookingClearance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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
