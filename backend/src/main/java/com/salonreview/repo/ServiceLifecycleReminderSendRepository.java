package com.salonreview.repo;

import com.salonreview.domain.ServiceLifecycleReminderSend;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;

public interface ServiceLifecycleReminderSendRepository extends JpaRepository<ServiceLifecycleReminderSend, Long> {
    boolean existsByBusinessIdAndAutomationKeyAndSquareCustomerIdAndTriggerServiceDate(
            Long businessId, String automationKey, String squareCustomerId, LocalDate triggerServiceDate);

    /** Cooldown check for a recurring (not one-shot) reminder — see
     * {@code ColorBoosterReminderScheduler}: a customer who was actually sent this automation
     * within the cooldown window isn't reconsidered, but one who was only ever skipped (no phone,
     * negative feedback, already booked) is re-evaluated fresh on every run — those facts can
     * change day to day. */
    boolean existsByBusinessIdAndAutomationKeyAndSquareCustomerIdAndStateAndCreatedAtAfter(
            Long businessId, String automationKey, String squareCustomerId, String state, Instant createdAtAfter);

    /** Of this automation's sends, how many customers have since had a NEW completed visit — same
     * "any subsequent provider_visit row counts, not verified against the specific follow-up
     * service" shape (and same acknowledged imprecision) as
     * {@code RepeatCustomerWinbackSendRepository#countConvertedSince} — see that method's own doc
     * for why a live per-customer Square check isn't done here either: this table has no per-
     * service identity to verify against without one, same reason
     * {@code TouchupReminderScheduler}/{@code ColorBoosterReminderScheduler} can't source
     * eligibility from {@code provider_visit} in the first place. {@code trigger_service_date} is
     * used as the "since" boundary regardless of whether it holds the qualifying procedure's own
     * date ({@code touchup_reminder}) or the day of evaluation ({@code color_booster_reminder}) —
     * both are always on or before the day the SMS actually went out, so either reads correctly as
     * "a visit after this counts." One method shared by both automations via {@code automationKey},
     * not a copy per automation — same table, same shape. */
    @Query(value = "SELECT COUNT(*) FROM service_lifecycle_reminder_send s "
            + "WHERE s.business_id = :businessId AND s.automation_key = :automationKey "
            + "AND s.state = :state AND s.created_at >= :since "
            + "AND EXISTS (SELECT 1 FROM provider_visit v "
            + "            WHERE v.business_id = :businessId AND v.customer_id = s.square_customer_id "
            + "              AND v.service_date > s.trigger_service_date)",
            nativeQuery = true)
    long countConvertedSince(@Param("businessId") Long businessId, @Param("automationKey") String automationKey,
                              @Param("state") String state, @Param("since") Instant since);
}
