package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * A manager's quick log entry: a customer wanted an appointment on {@link #requestedDate} (and
 * optionally {@link #requestedTime}) and there was nowhere to put them — every provider was
 * already booked. {@link #estimatedRevenue} is the manager's best estimate of what that visit
 * would have been worth. No real Square booking/order exists behind this (the slot was never
 * offered in the first place), so this is its own record rather than a note on something else —
 * see V121. Meant to build up a dataset for {@code /admin/missed-bookings}'s owner-facing analysis
 * (by month, by day of week) of whether demand is consistently outrunning capacity.
 */
@Entity
@Table(name = "missed_booking")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class MissedBooking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false)
    private Long businessId;

    @Column(name = "requested_date", nullable = false)
    private LocalDate requestedDate;

    @Column(name = "requested_time")
    private LocalTime requestedTime;

    @Column(name = "estimated_revenue", nullable = false)
    private BigDecimal estimatedRevenue;

    @Column(name = "service_name")
    private String serviceName;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
