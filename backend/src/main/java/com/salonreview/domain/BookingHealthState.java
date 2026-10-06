package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.time.Instant;

/** Per-check state of the online booking health checks (V163, BookingHealthCheckScheduler). */
@Entity
@Table(name = "booking_health_state")
@IdClass(BookingHealthState.Key.class)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class BookingHealthState {

    @Id
    @Column(name = "business_id")
    private Long businessId;

    @Id
    @Column(name = "check_key")
    private String checkKey;

    @Column(name = "fail_count", nullable = false)
    private int failCount;

    @Column(name = "alerted", nullable = false)
    private boolean alerted;

    @Column(name = "failing_since")
    private Instant failingSince;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @NoArgsConstructor @AllArgsConstructor @EqualsAndHashCode
    public static class Key implements Serializable {
        private Long businessId;
        private String checkKey;
    }
}
