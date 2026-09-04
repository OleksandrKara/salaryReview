package com.salonreview.repo;

import com.salonreview.domain.SameDayRebookingSend;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface SameDayRebookingSendRepository extends JpaRepository<SameDayRebookingSend, Long> {

    /** Idempotency guard against Square redelivering the same {@code payment.updated} event —
     * mirrors {@code SmsReplyFlowRepository}'s own payment-id check. Not business-scoped:
     * {@code square_payment_id} is Square's own globally unique id, checked before the caller
     * even knows which business the payment belongs to (see {@code SameDayRebookingTriggerService}). */
    boolean existsBySquarePaymentId(String squarePaymentId);

    /** Whether this phone number already got a same-day-rebooking nudge today — see
     * {@code SmsReplyFlowRepository#existsByBusinessIdAndPhoneNumberAndAutomationKeyAndCreatedAtAfter}'s
     * own doc for the incident this guards against (two family members checked out on separate
     * Square payments during the same visit). */
    boolean existsByBusinessIdAndPhoneNumberAndCreatedAtAfter(Long businessId, String phoneNumber, Instant after);

    List<SameDayRebookingSend> findByBusinessIdAndStateAndSendDueAtBefore(Long businessId, String state, Instant now);

    /** Of the automation's sends, how many customers have since completed a NEW visit — same
     * outcome definition and native-query reasoning as {@code LapsedCustomerWinbackSendRepository
     * #countConvertedSince}, anchored to this row's own {@code created_at} date (the day of the
     * qualifying checkout that triggered the send — this row is created right after that Square
     * payment, see SameDayRebookingTriggerService) rather than a dedicated visit-date column, since
     * this entity doesn't have one. */
    @Query(value = "SELECT COUNT(*) FROM same_day_rebooking_send s "
            + "WHERE s.business_id = :businessId AND s.state = :state AND s.created_at >= :since "
            + "AND EXISTS (SELECT 1 FROM provider_visit v "
            + "            WHERE v.business_id = :businessId AND v.customer_id = s.square_customer_id "
            + "              AND v.service_date > CAST(s.created_at AS date))",
            nativeQuery = true)
    long countConvertedSince(@Param("businessId") Long businessId, @Param("state") String state,
                              @Param("since") Instant since);
}
