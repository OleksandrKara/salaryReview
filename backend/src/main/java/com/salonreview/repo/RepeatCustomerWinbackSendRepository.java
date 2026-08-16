package com.salonreview.repo;

import com.salonreview.domain.RepeatCustomerWinbackSend;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface RepeatCustomerWinbackSendRepository extends JpaRepository<RepeatCustomerWinbackSend, Long> {

    /** Belt-and-suspenders alongside the eligibility query's own 60-day-cooldown {@code NOT EXISTS}
     * — see RepeatCustomerWinbackScheduler. Only a real {@code SENT} row counts against the
     * cooldown; a {@code SKIPPED_*} row (e.g. the automation was disabled that day) doesn't push
     * the customer's next eligible date out. */
    boolean existsByBusinessIdAndSquareCustomerIdAndStateAndCreatedAtAfter(
            Long businessId, String squareCustomerId, String state, Instant after);

    /** Of the automation's sends, how many customers have since completed a NEW visit — i.e. a
     * {@code provider_visit} row dated after the {@code last_visit_date} this send already knew
     * about at send time. This is the automation's actual business outcome (did the customer come
     * back), not a proxy like a click or a reply — see
     * {@code SmsAutomationRegistry.AutomationMeta#tracksConversion}. Native, not JPQL: the
     * correlated {@code EXISTS} against a different table (not an entity relation) isn't expressible
     * in HQL without a join we don't otherwise want. Filters both sides by business_id — s's own
     * column and provider_visit's (already business-scoped) — so a cross-tenant coincidence on
     * square_customer_id can never count as a conversion for the wrong business. */
    @Query(value = "SELECT COUNT(*) FROM repeat_customer_winback_send s "
            + "WHERE s.business_id = :businessId AND s.state = :state AND s.created_at >= :since "
            + "AND EXISTS (SELECT 1 FROM provider_visit v "
            + "            WHERE v.business_id = :businessId AND v.customer_id = s.square_customer_id "
            + "              AND v.service_date > s.last_visit_date)",
            nativeQuery = true)
    long countConvertedSince(@Param("businessId") Long businessId, @Param("state") String state,
                              @Param("since") Instant since);
}
