package com.salonreview.repo;

import com.salonreview.domain.SameDayRebookingSend;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface SameDayRebookingSendRepository extends JpaRepository<SameDayRebookingSend, Long> {

    /** Idempotency guard against Square redelivering the same {@code payment.updated} event —
     * mirrors {@code SmsReplyFlowRepository}'s own payment-id check. */
    boolean existsBySquarePaymentId(String squarePaymentId);

    List<SameDayRebookingSend> findByStateAndSendDueAtBefore(String state, Instant now);
}
