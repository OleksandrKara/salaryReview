package com.salonreview.repo;

import com.salonreview.domain.RepeatCustomerWinbackSend;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface RepeatCustomerWinbackSendRepository extends JpaRepository<RepeatCustomerWinbackSend, Long> {

    /** Belt-and-suspenders alongside the eligibility query's own 60-day-cooldown {@code NOT EXISTS}
     * — see RepeatCustomerWinbackScheduler. Only a real {@code SENT} row counts against the
     * cooldown; a {@code SKIPPED_*} row (e.g. the automation was disabled that day) doesn't push
     * the customer's next eligible date out. */
    boolean existsBySquareCustomerIdAndStateAndCreatedAtAfter(String squareCustomerId, String state, Instant after);
}
