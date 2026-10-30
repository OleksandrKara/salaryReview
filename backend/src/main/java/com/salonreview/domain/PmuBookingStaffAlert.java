package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "pmu_booking_staff_alert")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class PmuBookingStaffAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false)
    private Long businessId;

    @Column(name = "square_booking_id", nullable = false)
    private String squareBookingId;

    @Column(name = "service_names")
    private String serviceNames;

    @Column(name = "delivery_status", nullable = false)
    @Builder.Default
    private String deliveryStatus = "PENDING";

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "delivered_at")
    private Instant deliveredAt;
}
