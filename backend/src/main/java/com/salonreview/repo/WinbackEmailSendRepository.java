package com.salonreview.repo;

import com.salonreview.domain.WinbackEmailSend;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface WinbackEmailSendRepository extends JpaRepository<WinbackEmailSend, Long> {

    /** Idempotency check — one row per {@code sms_message.id}, ever. See
     * {@code WinbackEmailFallbackScheduler}. */
    boolean existsBySmsMessageId(Long smsMessageId);

    /** Real sends still missing an open or a click, within the sync window — see
     * {@code MailchimpActivitySyncScheduler}. Bounded by {@code since} so this never re-checks a
     * campaign from months ago (Mailchimp's own activity report doesn't change after a customer
     * has stopped looking at the email, so there's nothing to gain from checking indefinitely). */
    @Query("SELECT w FROM WinbackEmailSend w WHERE w.state = 'SENT' AND w.mailchimpCampaignId IS NOT NULL "
            + "AND w.createdAt >= :since AND (w.openedAt IS NULL OR w.emailClickedAt IS NULL)")
    List<WinbackEmailSend> findNeedingActivitySync(@Param("since") Instant since);

    /** Dashboard listing — one business, most-recent-first, within a window. Includes every state
     * (SENT and every SKIPPED_ / SEND_FAILED reason), so the owner can see why a candidate didn't
     * get an email, not just the ones that sent. */
    List<WinbackEmailSend> findByBusinessIdAndCreatedAtAfterOrderByCreatedAtDesc(Long businessId, Instant since);

    long countByBusinessIdAndStateAndCreatedAtAfter(Long businessId, String state, Instant since);

    long countByBusinessIdAndStateAndOpenedAtIsNotNullAndCreatedAtAfter(Long businessId, String state, Instant since);

    long countByBusinessIdAndStateAndEmailClickedAtIsNotNullAndCreatedAtAfter(Long businessId, String state, Instant since);

    /** Whether this customer completed a new visit after this specific email went out — the
     * dashboard's per-row "Converted" column. Same "a real completed visit, not a click" outcome
     * definition {@link RepeatCustomerWinbackSendRepository#countConvertedSince} already uses for
     * the SMS side, just anchored to this row's own send time instead of the customer's
     * pre-automation last-visit date. Called per-row rather than batched — the recent-window
     * listing this backs is small (a handful of rows/day for this business), not worth a more
     * complex batched query. */
    @Query(value = "SELECT EXISTS(SELECT 1 FROM provider_visit v WHERE v.business_id = :businessId "
            + "AND v.customer_id = :customerId AND v.service_date > CAST(:sentAt AS date))", nativeQuery = true)
    boolean hasConversionSince(@Param("businessId") Long businessId, @Param("customerId") String customerId,
                                @Param("sentAt") Instant sentAt);
}
