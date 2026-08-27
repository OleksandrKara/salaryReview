package com.salonreview.repo;

import com.salonreview.domain.SameDayRebookingSend;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
