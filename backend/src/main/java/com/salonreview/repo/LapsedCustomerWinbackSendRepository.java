package com.salonreview.repo;

import com.salonreview.domain.LapsedCustomerWinbackSend;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LapsedCustomerWinbackSendRepository extends JpaRepository<LapsedCustomerWinbackSend, Long> {

    /** Belt-and-suspenders alongside the eligibility query's own {@code NOT EXISTS} — see
     * LapsedCustomerWinbackScheduler. Once a customer has any row here, they're never reconsidered
     * (one-shot per customer, not per-visit — see design.md D4). */
    boolean existsByBusinessIdAndSquareCustomerId(Long businessId, String squareCustomerId);
}
