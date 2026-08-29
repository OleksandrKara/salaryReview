package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;

/**
 * A local, raw copy of one Square Booking — backfilled and kept fresh via webhook + periodic
 * reconciliation (see {@code SquareBookingMirrorIngestService}), so marketing reads (which
 * previously called {@code SquareClient#bookingsForCustomer} live, once per contact) can query
 * this table instead. Deliberately unmatched, unmodified Square data — not a derived record like
 * {@link ProviderVisit}. Payroll was an explicit Phase 1 non-goal; since Phase 2f, {@code
 * SquareMonthAggregator#aggregateFromMirror} reads it too (the shadow-diff twin of the still-live
 * {@code aggregate()}, ahead of the eventual cutover).
 */
@Entity
@Table(name = "square_booking")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class SquareBookingMirror {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false)
    private Long businessId;

    @Column(name = "square_booking_id", nullable = false)
    private String squareBookingId;

    /** Raw Square customer id — not resolved through {@code SquareClient#canonicalCustomerIds}.
     * Callers must apply that resolution themselves, same as every other Square-customer-id-keyed
     * read in this codebase (see SquareMonthAggregator/PrepaidService/ProviderVisitIngestService). */
    @Column(name = "square_customer_id")
    private String squareCustomerId;

    @Column(nullable = false)
    private String status;

    @Column(name = "start_at")
    private Instant startAt;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "location_id")
    private String locationId;

    @Column(name = "seller_note")
    private String sellerNote;

    @Column(name = "customer_note")
    private String customerNote;

    /** One entry per Square {@code AppointmentSegment} — team member, service variation, duration.
     * No independent lifecycle of its own, so stored inline rather than as a child table. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "appointment_segments_json", columnDefinition = "jsonb")
    private List<Segment> appointmentSegments;

    @Column(name = "synced_at", nullable = false)
    @Builder.Default
    private Instant syncedAt = Instant.now();

    public record Segment(String teamMemberId, String serviceVariationId, Integer durationMinutes) {}
}
