package com.salonreview.repo;

import com.salonreview.domain.LapsedCustomerWinbackSend;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface LapsedCustomerWinbackSendRepository extends JpaRepository<LapsedCustomerWinbackSend, Long> {

    /** Belt-and-suspenders alongside the eligibility query's own {@code NOT EXISTS} — see
     * LapsedCustomerWinbackScheduler. Once a customer has any row here, they're never reconsidered
     * (one-shot per customer, not per-visit — see design.md D4). */
    boolean existsByBusinessIdAndSquareCustomerId(Long businessId, String squareCustomerId);

    /** Of the automation's sends, how many customers have since completed a NEW visit — same
     * outcome definition and native-query reasoning as
     * {@link RepeatCustomerWinbackSendRepository#countConvertedSince}, just anchored to this row's
     * own {@code visit_date} column instead of {@code last_visit_date}. */
    @Query(value = "SELECT COUNT(*) FROM lapsed_customer_winback_send s "
            + "WHERE s.business_id = :businessId AND s.state = :state AND s.created_at >= :since "
            + "AND EXISTS (SELECT 1 FROM provider_visit v "
            + "            WHERE v.business_id = :businessId AND v.customer_id = s.square_customer_id "
            + "              AND v.service_date > s.visit_date)",
            nativeQuery = true)
    long countConvertedSince(@Param("businessId") Long businessId, @Param("state") String state,
                              @Param("since") Instant since);
}
