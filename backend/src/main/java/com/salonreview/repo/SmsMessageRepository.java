package com.salonreview.repo;

import com.salonreview.domain.SmsMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SmsMessageRepository extends JpaRepository<SmsMessage, Long> {

    /** One row per distinct phone number this salon has ever texted with, most-recent-message-
     * first — backs the manager conversation view's contact list (see
     * openspec/changes/lead-followup-and-manager-inbox design.md D8). */
    interface ConversationSummaryProjection {
        String getPhoneNumber();
        Instant getLastMessageAt();
        String getLastMessageBody();
        String getLastMessageDirection();
        Long getUnreadCount();
        /** Twilio delivery status of the last message, if it was OUTBOUND and a delivery-status
         * callback has arrived — {@code null} otherwise. Lets the contact list flag "the most
         * recent message never reached this customer" without opening the thread. */
        String getLastMessageDeliveryStatus();
        String getLastMessageDeliveryErrorMessage();
        /** Whether this phone number has *ever* left a low-rating reply, not just on the last
         * message — this is a permanent flag, so it stays true even once the conversation moves
         * on to friendlier messages. See {@code SmsMessage#negativeFeedbackAt}. */
        boolean getHasNegativeFeedback();
    }

    @Query(value = """
            SELECT latest.phone_number AS phoneNumber,
                   latest.last_message_at AS lastMessageAt,
                   latest.last_message_body AS lastMessageBody,
                   latest.last_message_direction AS lastMessageDirection,
                   COALESCE(unread.unread_count, 0) AS unreadCount,
                   latest.last_message_delivery_status AS lastMessageDeliveryStatus,
                   latest.last_message_delivery_error_message AS lastMessageDeliveryErrorMessage,
                   COALESCE(negative.has_negative_feedback, false) AS hasNegativeFeedback
            FROM (
                SELECT DISTINCT ON (phone_number) phone_number,
                       created_at AS last_message_at,
                       body AS last_message_body,
                       direction AS last_message_direction,
                       delivery_status AS last_message_delivery_status,
                       delivery_error_message AS last_message_delivery_error_message
                FROM sms_message
                ORDER BY phone_number, created_at DESC
            ) latest
            LEFT JOIN (
                SELECT phone_number, COUNT(*) AS unread_count
                FROM sms_message
                WHERE direction = 'INBOUND' AND read_at IS NULL
                GROUP BY phone_number
            ) unread ON unread.phone_number = latest.phone_number
            LEFT JOIN (
                SELECT phone_number, true AS has_negative_feedback
                FROM sms_message
                WHERE negative_feedback_at IS NOT NULL
                GROUP BY phone_number
            ) negative ON negative.phone_number = latest.phone_number
            ORDER BY latest.last_message_at DESC
            """, nativeQuery = true)
    List<ConversationSummaryProjection> conversationSummaries();

    /** Full chronological thread for one phone number — backs the manager conversation view's
     * selected-thread panel. */
    List<SmsMessage> findByPhoneNumberOrderByCreatedAtAsc(String phoneNumber);

    /** Most-recent-first outbound messages to this phone number, capped at 20 — used by
     * {@code SmsReactionService} to match an inbound Apple tapback-over-SMS text (e.g.
     * {@code Loved "message"}) against the message it's reacting to. Capped rather than scanning
     * the whole thread: a customer only ever taps a message they can currently see, which in
     * practice is always one of the salon's most recent sends. */
    List<SmsMessage> findTop20ByPhoneNumberAndDirectionOrderByCreatedAtDesc(String phoneNumber, String direction);

    /** The single most recent outbound message to this phone number, regardless of automation —
     * used by {@code TwilioInboundSmsController}'s reply-attribution fallback (see
     * {@code SmsMessageLogService#mostRecentAutomationKey}) for every automation that doesn't open
     * an {@code SmsReplyFlow} the way {@code checkout_review_request} does. */
    Optional<SmsMessage> findFirstByPhoneNumberAndDirectionOrderByCreatedAtDesc(String phoneNumber, String direction);

    /** Backs the click-tracked {@code /r/{token}} short link — see V53, design.md D6. */
    Optional<SmsMessage> findByClickToken(String clickToken);

    /** Backs {@link com.salonreview.sms.TwilioStatusCallbackController} — matches an incoming
     * delivery-status callback back to the row it was sent from. */
    Optional<SmsMessage> findByTwilioMessageSid(String twilioMessageSid);

    /** Used to re-roll a freshly generated {@link com.salonreview.sms.ClickTokens} candidate on
     * the (extremely rare) chance it collides with one already in use — see design.md D6. */
    boolean existsByClickToken(String clickToken);

    /** Whether this phone number has ever actually followed a click-tracked link to a given
     * target (e.g. {@code GOOGLE_REVIEW}) — used by the checkout-review-request automation to
     * avoid asking a proven repeat reviewer for another public Google review every time they rate
     * 5 stars again (see {@code CheckoutReviewReplyService}). {@code phoneNumber} must already be
     * E.164-normalized — this table only ever stores normalized numbers (see
     * {@code SmsMessageLogService}'s own doc comment), so no tolerant matching is needed here. */
    boolean existsByPhoneNumberAndLinkTargetAndClickedAtIsNotNull(String phoneNumber, String linkTarget);

    /** Batch form of {@link #existsByPhoneNumberAndLinkTargetAndClickedAtIsNotNull} — one query for
     * every row on the manager conversation view's list page (which shows a quick-glance icon for
     * "has clicked this link type before"), not one query per row. See
     * {@code BlockedNumberRepository#findByPhoneNumberIn}'s own doc comment for the same pattern. */
    @Query("SELECT DISTINCT m.phoneNumber FROM SmsMessage m "
            + "WHERE m.phoneNumber IN :phoneNumbers AND m.linkTarget = :linkTarget AND m.clickedAt IS NOT NULL")
    List<String> findPhoneNumbersWithClickedLinkTarget(@Param("phoneNumbers") Collection<String> phoneNumbers,
                                                        @Param("linkTarget") String linkTarget);

    /** Whether this phone number has ever left a low-rating reply to the checkout-review-request
     * automation — permanently excludes them from the same-day-rebooking win-back nudge (see
     * {@code SameDayRebookingScheduler}). {@code phoneNumber} must already be E.164-normalized. */
    boolean existsByPhoneNumberAndNegativeFeedbackAtIsNotNull(String phoneNumber);

    /** Batch form of "has any OUTBOUND message to this number ever come back with one of these
     * Twilio delivery-status error codes" — same one-query-not-one-per-row pattern as
     * {@link #findPhoneNumbersWithClickedLinkTarget}. Backs the conversation list's spam-flag icon
     * — see {@code SmsMessageLogService#phoneNumbersFlaggedAsSpam} for which codes count. */
    @Query("SELECT DISTINCT m.phoneNumber FROM SmsMessage m "
            + "WHERE m.phoneNumber IN :phoneNumbers AND m.deliveryErrorCode IN :errorCodes")
    List<String> findPhoneNumbersWithDeliveryErrorCode(@Param("phoneNumbers") Collection<String> phoneNumbers,
                                                        @Param("errorCodes") Collection<String> errorCodes);

    /** Most recent time this phone number was sent a click-tracked link to the given target
     * (any outbound message with that {@code link_target}, sent or not — "sent" here means
     * "we tried", matching how the contact sidebar wants to distinguish "never sent" from "sent
     * but not yet clicked"), or {@code null} if never. See {@code SmsMessageLogService#linkEngagement}. */
    @Query("SELECT MAX(m.createdAt) FROM SmsMessage m "
            + "WHERE m.phoneNumber = :phoneNumber AND m.linkTarget = :linkTarget AND m.direction = 'OUTBOUND'")
    Instant findLatestLinkSentAt(@Param("phoneNumber") String phoneNumber, @Param("linkTarget") String linkTarget);

    /** Most recent time this phone number actually clicked through a link to the given target, or
     * {@code null} if never — see {@code SmsMessageLogService#linkEngagement}. */
    @Query("SELECT MAX(m.clickedAt) FROM SmsMessage m WHERE m.phoneNumber = :phoneNumber AND m.linkTarget = :linkTarget")
    Instant findLatestLinkClickedAt(@Param("phoneNumber") String phoneNumber, @Param("linkTarget") String linkTarget);

    /** Backs the hub's unread-count badge — every unread inbound message, regardless of whether
     * it ever matched an automation. */
    long countByDirectionAndReadAtIsNull(String direction);

    /** Marks every unread inbound message in one phone number's thread read in a single write —
     * backs the manager conversation view's "opening a thread marks it read" behavior (see
     * SmsMessageLogService#markThreadRead). Bulk, not a loop of the single-message endpoint: a
     * thread can have many unread messages, and this is one round trip instead of N. */
    @Modifying
    @Query("UPDATE SmsMessage m SET m.readAt = :now "
            + "WHERE m.phoneNumber = :phoneNumber AND m.direction = 'INBOUND' AND m.readAt IS NULL")
    void markThreadRead(@Param("phoneNumber") String phoneNumber, @Param("now") Instant now);

    /** "Mark as unread" (see SmsMessageLogService#markThreadUnread) — un-reads only the most
     * recent inbound message in the thread, same convention as every mainstream messaging client:
     * the point is a "come back to this" flag on the conversation, not un-reading every message
     * that was ever read. A no-op (0 rows) for a thread with no inbound messages at all — nothing
     * sensible to flag unread there. Native, not JPQL: HQL doesn't support a correlated
     * ORDER BY ... LIMIT 1 subquery, which Postgres does. */
    @Modifying
    @Query(value = "UPDATE sms_message SET read_at = NULL WHERE id = ("
            + "  SELECT id FROM sms_message WHERE phone_number = :phoneNumber AND direction = 'INBOUND'"
            + "  ORDER BY created_at DESC LIMIT 1)", nativeQuery = true)
    void markLastInboundUnread(@Param("phoneNumber") String phoneNumber);

    /** 30-day "sent count" shown per automation card — real sends only, not blocked attempts. */
    long countByAutomationKeyAndDirectionAndStatusAndCreatedAtAfter(
            String automationKey, String direction, String status, Instant since);

    /** Same as above, further narrowed to one template — only meaningful for
     * {@code checkout_review_request} (see {@code SmsAutomationRegistry.AutomationMeta#primaryTemplateKey}),
     * whose conditional branch reply logs a second template under the same automationKey; without
     * this filter the "sent" count on that automation's card would double-count a single completed
     * conversation (the rating ask + the branch reply). */
    long countByAutomationKeyAndTemplateKeyAndDirectionAndStatusAndCreatedAtAfter(
            String automationKey, String templateKey, String direction, String status, Instant since);

    /** How many of an automation's 30-day sends carried a click-tracked link at all — the
     * denominator for the automation card's click-through rate (see
     * {@code SmsAutomationRegistry.AutomationMeta#tracksClicks}). */
    long countByAutomationKeyAndDirectionAndStatusAndLinkTargetIsNotNullAndCreatedAtAfter(
            String automationKey, String direction, String status, Instant since);

    /** The numerator for the same click-through rate — link-carrying sends that were actually
     * clicked. */
    long countByAutomationKeyAndDirectionAndStatusAndLinkTargetIsNotNullAndClickedAtIsNotNullAndCreatedAtAfter(
            String automationKey, String direction, String status, Instant since);

    /** 30-day inbound-reply count for an automation — only meaningful for automations that open an
     * {@code SmsReplyFlow} (currently just {@code checkout_review_request}, see
     * {@code SmsAutomationRegistry.AutomationMeta#tracksReplies}), since that's the only path that
     * ever tags an INBOUND row with an automationKey in the first place. */
    long countByAutomationKeyAndDirectionAndCreatedAtAfter(
            String automationKey, String direction, Instant since);

    /** Newest unread inbound rows first, for the inbox view's default sort. */
    List<SmsMessage> findByDirectionAndReadAtIsNullOrderByCreatedAtDesc(String direction);

    // Parameters are explicitly CAST to string — a bare `:phoneNumber` inside CONCAT()/LIKE, when
    // bound null (the common case: no filter applied), leaves Postgres unable to infer its type
    // and it defaults to bytea, which then fails "operator does not exist: text ~~ bytea" against
    // the LIKE operator. The cast fixes the type for both the null-check and the LIKE branch.
    @Query("""
            SELECT m FROM SmsMessage m
            WHERE (:phoneNumber IS NULL OR m.phoneNumber LIKE CONCAT('%', CAST(:phoneNumber AS string), '%'))
              AND (:direction IS NULL OR m.direction = CAST(:direction AS string))
              AND (:automationKey IS NULL OR m.automationKey = CAST(:automationKey AS string))
            ORDER BY m.createdAt DESC
            """)
    Page<SmsMessage> search(@Param("phoneNumber") String phoneNumber,
                             @Param("direction") String direction,
                             @Param("automationKey") String automationKey,
                             Pageable pageable);

    /** Newest matching message first, across every phone number — backs the manager conversation
     * view's search box for matches buried in a thread's older history (name/phone matching is
     * done client-side against the already-loaded conversation list; see
     * {@code SmsMessageLogService#searchConversations}). */
    @Query("SELECT m FROM SmsMessage m WHERE LOWER(m.body) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')) "
            + "ORDER BY m.createdAt DESC")
    List<SmsMessage> searchByBodyContaining(@Param("q") String q, Pageable pageable);
}
