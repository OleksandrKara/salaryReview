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

    /** Idempotency check for a pure-email campaign with no {@code sms_message_id} to key off of
     * (see {@code ColorBoosterWinbackOneOffService}) — has this exact customer already been
     * genuinely sent this automation, ever. */
    boolean existsByBusinessIdAndAutomationKeyAndSquareCustomerIdAndState(
            Long businessId, String automationKey, String squareCustomerId, String state);

    /** Upsert target for the same campaign — finds the one row (if any) already logged for this
     * customer under this automation, so a retry updates it in place instead of risking a second
     * row for the same real send (no DB-level unique constraint covers this shape the way
     * {@code sms_message_id} does for the SMS-fallback automations). */
    java.util.Optional<WinbackEmailSend> findByBusinessIdAndAutomationKeyAndSquareCustomerId(
            Long businessId, String automationKey, String squareCustomerId);

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

    /** Same listing, unbounded by a window — backs the manager conversation view's "Emails" tab
     * (see {@code SmsActivityController#emailSends}), a flat historical log rather than a
     * recent-activity dashboard. */
    List<WinbackEmailSend> findByBusinessIdOrderByCreatedAtDesc(Long businessId);

    long countByBusinessIdAndStateAndCreatedAtAfter(Long businessId, String state, Instant since);

    long countByBusinessIdAndStateAndOpenedAtIsNotNullAndCreatedAtAfter(Long businessId, String state, Instant since);

    long countByBusinessIdAndStateAndEmailClickedAtIsNotNullAndCreatedAtAfter(Long businessId, String state, Instant since);

    /** Per-automation forms of the three counts above — backs each automation card's own email
     * stats line (see {@code SmsAutomationService#list}), as opposed to the Email tab's dashboard,
     * which aggregates across every automation. */
    long countByBusinessIdAndAutomationKeyAndStateAndCreatedAtAfter(
            Long businessId, String automationKey, String state, Instant since);

    long countByBusinessIdAndAutomationKeyAndStateAndOpenedAtIsNotNullAndCreatedAtAfter(
            Long businessId, String automationKey, String state, Instant since);

    long countByBusinessIdAndAutomationKeyAndStateAndEmailClickedAtIsNotNullAndCreatedAtAfter(
            Long businessId, String automationKey, String state, Instant since);

    /** The follow-up email tied to a specific SMS send, if one exists — backs the manager
     * conversation view's inline "email sent that evening" annotation under the original SMS
     * bubble (see {@code SmsMessageLogService#thread}). At most one row per {@code sms_message.id}
     * (unique constraint — see V130). */
    java.util.Optional<com.salonreview.domain.WinbackEmailSend> findBySmsMessageId(Long smsMessageId);

    /** Batch form of {@link #findBySmsMessageId} — one query per thread load, not one per message
     * row, same "batch not per-row" convention {@code SmsActivityController#enrich} already follows
     * for every other per-message annotation (media, reactions, link-click flags). */
    List<com.salonreview.domain.WinbackEmailSend> findBySmsMessageIdIn(java.util.Collection<Long> smsMessageIds);

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

    /** Aggregate form of {@link #hasConversionSince}, per automation — backs each automation
     * card's own "returned" stat for the email channel specifically (see {@code
     * SmsAutomationService#list}), same "did they actually come back" outcome definition as the
     * SMS-side {@code countConvertedSince} methods, just anchored to this row's own send time
     * rather than a pre-automation last-visit/visit-date column. Only counts real {@code STATE_SENT}
     * rows — a {@code SKIPPED_*}/{@code SEND_FAILED} row was never actually delivered, so crediting
     * a later visit to it would overstate what this specific email accomplished. */
    @Query(value = "SELECT COUNT(*) FROM winback_email_send w "
            + "WHERE w.business_id = :businessId AND w.automation_key = :automationKey "
            + "AND w.state = :state AND w.created_at >= :since "
            + "AND EXISTS (SELECT 1 FROM provider_visit v "
            + "            WHERE v.business_id = :businessId AND v.customer_id = w.square_customer_id "
            + "              AND v.service_date > CAST(w.created_at AS date))",
            nativeQuery = true)
    long countConvertedSince(@Param("businessId") Long businessId, @Param("automationKey") String automationKey,
                              @Param("state") String state, @Param("since") Instant since);

    /** {@code checkout_review_request}'s own "returned" stat for the email channel — "converted"
     * for a satisfaction-rating automation isn't "came back for a paid visit" (there's no
     * discount/incentive here, so {@link #countConvertedSince} would always read 0), it's "the
     * customer actually rated their visit" — i.e. the {@link com.salonreview.domain.SmsReplyFlow}
     * this row's own {@code sms_message.id} was the "ask" for reached {@code COMPLETED} (see
     * {@code CheckoutReviewRatingController}, which is the only thing that can complete a flow
     * already past its 24h SMS window). See {@code SmsAutomationService#list}. */
    @Query(value = "SELECT COUNT(*) FROM winback_email_send w "
            + "JOIN sms_reply_flow f ON f.ask_sms_message_id = w.sms_message_id "
            + "AND f.automation_key = w.automation_key "
            + "WHERE w.business_id = :businessId AND w.automation_key = :automationKey "
            + "AND w.state = :state AND w.created_at >= :since AND f.state = 'COMPLETED'",
            nativeQuery = true)
    long countRespondedSince(@Param("businessId") Long businessId, @Param("automationKey") String automationKey,
                             @Param("state") String state, @Param("since") Instant since);
}
