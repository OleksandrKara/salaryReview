package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One (business, provider) pair's known-available slot-start time, as of the last poll — see
 * {@code ProviderScheduleClosureAlertScheduler}, which diffs a fresh Square availability read
 * against these rows to find slots that disappeared since the previous poll, then replaces the
 * whole set for that provider with the fresh read. Not an append-only history; a row surviving
 * from poll to poll just means that slot is still open.
 */
@Entity
@Table(name = "provider_availability_snapshot")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class ProviderAvailabilitySnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false)
    private Long businessId;

    @Column(name = "team_member_id", nullable = false)
    private String teamMemberId;

    @Column(name = "slot_start_at", nullable = false)
    private Instant slotStartAt;

    @Column(name = "captured_at", nullable = false)
    @Builder.Default
    private Instant capturedAt = Instant.now();
}
