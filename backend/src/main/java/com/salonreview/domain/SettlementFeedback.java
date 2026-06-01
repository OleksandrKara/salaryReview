package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A provider's response to one period (half) of their settlement: APPROVED, or CHANGES_REQUESTED with
 * a comment the owner/manager sees on the report. One row per provider/year/month/half (re-submitting
 * updates it). ({@code created_at} is DB-managed; {@code updated_at} is set by the service per write.)
 */
@Entity
@Table(name = "settlement_feedback")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class SettlementFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "provider_id", nullable = false)
    private Long providerId;

    @Column(nullable = false)
    private int year;

    @Column(nullable = false)
    private int month;

    /** Which period this response is for (1-15 or 16-end). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Half half;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FeedbackStatus status;

    @Column
    private String comment;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
