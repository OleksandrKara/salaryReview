package com.salonreview.repo;

import com.salonreview.domain.SquareBookingMirror;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

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
