package com.salonreview.repo;

import com.salonreview.domain.SquareBookingMirror;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SquareBookingMirrorRepository extends JpaRepository<SquareBookingMirror, Long> {

    /** A customer's mirrored bookings since a given instant — the local replacement for
     * {@code SquareClient#bookingsForCustomer(customerId, since)}. Callers pass the *canonical*
     * Square customer id (see {@link SquareBookingMirror}'s own doc on why the stored id is raw). */
    List<SquareBookingMirror> findByBusinessIdAndSquareCustomerIdAndStartAtAfter(
            Long businessId, String squareCustomerId, Instant since);

    /** Every mirrored booking for several customers at once, since a given instant — batches what
     * would otherwise be one query per customer (see {@code MarketingAnalyticsService
     * #bookingHistoryByCustomer}, which used to make one live Square call per customer for exactly
     * this reason). */
    List<SquareBookingMirror> findByBusinessIdAndSquareCustomerIdInAndStartAtAfter(
            Long businessId, List<String> squareCustomerIds, Instant since);

    /** Every mirrored booking for a business in a window, independent of customer — the local
     * replacement for {@code SquareClient#bookings(from, to)} (Phase 2), needed by {@code
     * SquareMonthAggregator}, which matches a whole month's bookings against orders/cash-notes at
     * once rather than one customer at a time. */
    List<SquareBookingMirror> findByBusinessIdAndStartAtBetween(Long businessId, Instant from, Instant to);

    /** One business's own single booking by Square's own id — used by {@code
     * PreVisitNurtureScheduler} both to find newly-created bookings to welcome (via {@code
     * createdAt} rather than {@code startAt}) and to re-check a booking's current status right
     * before sending its day-before reminder (a cancellation between the welcome email and then
     * must not get a reminder for a visit that's no longer happening). */
    Optional<SquareBookingMirror> findByBusinessIdAndSquareBookingId(Long businessId, String squareBookingId);

    /** Recently-created bookings still in a given status, for one business — the welcome email's
     * own trigger query. Bounded scan (a booking created outside this window is simply never
     * welcomed), same "bounded, not indefinite" shape every poller in this codebase already uses. */
    List<SquareBookingMirror> findByBusinessIdAndStatusAndCreatedAtBetween(
            Long businessId, String status, Instant createdAfter, Instant createdBefore);

    /** Insert-or-update by the natural key (business + Square's own booking id) — used by both the
     * bulk window ingest (backfill/reconciliation) and the single-event webhook path. Native, not a
     * derived Spring Data method: JPA has no portable "upsert," and re-reading then re-saving one
     * row at a time for a whole month's bookings would be needlessly slow. */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO square_booking (business_id, square_booking_id, square_customer_id, status,
                start_at, created_at, updated_at, location_id, seller_note, customer_note,
                appointment_segments_json, synced_at)
            VALUES (:businessId, :squareBookingId, :squareCustomerId, :status,
                :startAt, :createdAt, :updatedAt, :locationId, :sellerNote, :customerNote,
                CAST(:segmentsJson AS jsonb), now())
            ON CONFLICT (business_id, square_booking_id) DO UPDATE SET
                square_customer_id = EXCLUDED.square_customer_id,
                status = EXCLUDED.status,
                start_at = EXCLUDED.start_at,
                created_at = EXCLUDED.created_at,
                updated_at = EXCLUDED.updated_at,
                location_id = EXCLUDED.location_id,
                seller_note = EXCLUDED.seller_note,
                customer_note = EXCLUDED.customer_note,
                appointment_segments_json = EXCLUDED.appointment_segments_json,
                synced_at = now()
            """, nativeQuery = true)
    void upsert(@Param("businessId") Long businessId, @Param("squareBookingId") String squareBookingId,
                @Param("squareCustomerId") String squareCustomerId, @Param("status") String status,
                @Param("startAt") Instant startAt, @Param("createdAt") Instant createdAt,
                @Param("updatedAt") Instant updatedAt, @Param("locationId") String locationId,
                @Param("sellerNote") String sellerNote, @Param("customerNote") String customerNote,
                @Param("segmentsJson") String segmentsJson);
}
