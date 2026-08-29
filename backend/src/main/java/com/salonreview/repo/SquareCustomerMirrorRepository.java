package com.salonreview.repo;

import com.salonreview.domain.SquareCustomerMirror;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public interface SquareCustomerMirrorRepository extends JpaRepository<SquareCustomerMirror, Long> {

    /** Every mirrored customer with this phone number for a business — the local replacement for
     * {@code SquareClient#customerIdsForPhone(phoneNumber)}. Callers must normalize the phone the
     * same way {@code SquareClient#normalizePhone} does before calling this, or it won't match. */
    List<SquareCustomerMirror> findByBusinessIdAndPhoneNumber(Long businessId, String phoneNumber);

    /** Insert-or-update by the natural key (business + Square's own customer id) — used by both the
     * full-directory backfill/re-sync and the single-event webhook path. Native, not a derived
     * Spring Data method, matching every other mirror repository's own upsert (see
     * {@code SquareBookingMirrorRepository#upsert}). */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO square_customer (business_id, square_customer_id, phone_number, given_name,
                family_name, email_address, square_created_at, synced_at)
            VALUES (:businessId, :squareCustomerId, :phoneNumber, :givenName,
                :familyName, :emailAddress, :squareCreatedAt, now())
            ON CONFLICT (business_id, square_customer_id) DO UPDATE SET
                phone_number = EXCLUDED.phone_number,
                given_name = EXCLUDED.given_name,
                family_name = EXCLUDED.family_name,
                email_address = EXCLUDED.email_address,
                square_created_at = EXCLUDED.square_created_at,
                synced_at = now()
            """, nativeQuery = true)
    void upsert(@Param("businessId") Long businessId, @Param("squareCustomerId") String squareCustomerId,
                @Param("phoneNumber") String phoneNumber, @Param("givenName") String givenName,
                @Param("familyName") String familyName, @Param("emailAddress") String emailAddress,
                @Param("squareCreatedAt") Instant squareCreatedAt);

    /** Removes a customer mirror row on {@code customer.deleted} — see {@code
     * SquareCustomerWebhookHandler}. Square's own delete keeps the row out of every future full
     * re-sync too, so this isn't just a webhook nicety; without it, a deleted-then-phone-reused
     * customer's now-stale row would keep resolving as if it still existed. */
    @Transactional
    void deleteByBusinessIdAndSquareCustomerId(Long businessId, String squareCustomerId);
}
