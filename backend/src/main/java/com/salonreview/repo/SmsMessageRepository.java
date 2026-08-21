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
                WHERE business_id = :businessId
                ORDER BY phone_number, created_at DESC
            ) latest
            LEFT JOIN (
                SELECT phone_number, COUNT(*) AS unread_count
                FROM sms_message
                WHERE business_id = :businessId AND direction = 'INBOUND' AND read_at IS NULL
                GROUP BY phone_number
            ) unread ON unread.phone_number = latest.phone_number
            LEFT JOIN (
                SELECT phone_number, true AS has_negative_feedback
                FROM sms_message
                WHERE business_id = :businessId AND negative_feedback_at IS NOT NULL
                GROUP BY phone_number
            ) negative ON negative.phone_number = latest.phone_number
            ORDER BY latest.last_message_at DESC
            """, nativeQuery = true)
    List<ConversationSummaryProjection> conversationSummaries(@Param("businessId") Long businessId);

    /** Cursor-paginated form of {@link #conversationSummaries}. */
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
                WHERE business_id = :businessId
                ORDER BY phone_number, created_at DESC
            ) latest
            LEFT JOIN (
                SELECT phone_number, COUNT(*) AS unread_count
                FROM sms_message
                WHERE business_id = :businessId AND direction = 'INBOUND' AND read_at IS NULL
                GROUP BY phone_number
            ) unread ON unread.phone_number = latest.phone_number
            LEFT JOIN (
                SELECT phone_number, true AS has_negative_feedback
                FROM sms_message
                WHERE business_id = :businessId AND negative_feedback_at IS NOT NULL
                GROUP BY phone_number
            ) negative ON negative.phone_number = latest.phone_number
            WHERE (CAST(:cursor AS timestamptz) IS NULL OR latest.last_message_at < CAST(:cursor AS timestamptz))
            ORDER BY latest.last_message_at DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<ConversationSummaryProjection> conversationSummariesPage(@Param("businessId") Long businessId,
                                                                    @Param("cursor") Instant cursor,
                                                                    @Param("limit") int limit);

    /** Single-conversation form of {@link #conversationSummaries}, for one phone number. Empty
     * when this phone number has no messages at all for this business. */
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
                SELECT phone_number, created_at AS last_message_at, body AS last_message_body,
                       direction AS last_message_direction,
                       delivery_status AS last_message_delivery_status,
                       delivery_error_message AS last_message_delivery_error_message
                FROM sms_message
                WHERE business_id = :businessId AND phone_number = :phoneNumber
                ORDER BY created_at DESC
                LIMIT 1
            ) latest
            LEFT JOIN (
                SELECT phone_number, COUNT(*) AS unread_count
                FROM sms_message
                WHERE business_id = :businessId AND direction = 'INBOUND' AND read_at IS NULL AND phone_number = :phoneNumber
                GROUP BY phone_number
            ) unread ON unread.phone_number = latest.phone_number
            LEFT JOIN (
                SELECT phone_number, true AS has_negative_feedback
                FROM sms_message
                WHERE business_id = :businessId AND negative_feedback_at IS NOT NULL AND phone_number = :phoneNumber
                GROUP BY phone_number
            ) negative ON negative.phone_number = latest.phone_number
            """, nativeQuery = true)
    Optional<ConversationSummaryProjection> conversationSummaryForPhone(@Param("businessId") Long businessId,
                                                                          @Param("phoneNumber") String phoneNumber);

    /** Full chronological thread for one phone number, for one business — backs the manager
     * conversation view's selected-thread panel. */
    List<SmsMessage> findByBusinessIdAndPhoneNumberOrderByCreatedAtAsc(Long businessId, String phoneNumber);

    /** Most-recent-first outbound messages to this phone number, capped at 20, for one business —
     * used by {@code SmsReactionService} to match an inbound Apple tapback-over-SMS text against
     * the message it's reacting to. */
    List<SmsMessage> findTop20ByBusinessIdAndPhoneNumberAndDirectionOrderByCreatedAtDesc(
            Long businessId, String phoneNumber, String direction);

    /** The single most recent outbound message to this phone number, for one business, regardless
     * of automation — used by {@code TwilioInboundSmsController}'s reply-attribution fallback. */
    Optional<SmsMessage> findFirstByBusinessIdAndPhoneNumberAndDirectionOrderByCreatedAtDesc(
            Long businessId, String phoneNumber, String direction);

    /** Backs the click-tracked {@code /r/{token}} short link — see V53, design.md D6. Not
     * business-scoped: the token is globally unique and self-identifying (the row it resolves to
     * already carries its own business_id), same reasoning as {@code RagIngestionService}
     * deriving correctness from the row rather than needing an external business hint — see
     * {@code ShortLinkController}, a public unauthenticated endpoint with no business context of
     * its own to filter by. */
    Optional<SmsMessage> findByClickToken(String clickToken);

    /** Backs {@link com.salonreview.sms.TwilioStatusCallbackController} — matches an incoming
     * delivery-status callback back to the row it was sent from. Not business-scoped, same
     * reasoning as {@link #findByClickToken}: {@code twilio_message_sid} is Twilio's own globally
     * unique identifier, and the row it resolves to already carries its own business_id. */
    Optional<SmsMessage> findByTwilioMessageSid(String twilioMessageSid);

    /** Used to re-roll a freshly generated {@link com.salonreview.sms.ClickTokens} candidate on
     * the (extremely rare) chance it collides with one already in use — see design.md D6. Not
     * business-scoped: {@code click_token} must be globally unique across every business sharing
     * this table, not just unique within one business, or two businesses' tokens could collide on
     * the shared {@code /r/{token}} route. */
    boolean existsByClickToken(String clickToken);

    /** Whether this phone number has ever actually followed a click-tracked link to a given
     * target (e.g. {@code GOOGLE_REVIEW}), for one business — used by the checkout-review-request
     * automation to avoid asking a proven repeat reviewer for another public Google review every
     * time they rate 5 stars again (see {@code CheckoutReviewReplyService}). {@code phoneNumber}
     * must already be E.164-normalized. */
    boolean existsByBusinessIdAndPhoneNumberAndLinkTargetAndClickedAtIsNotNull(
            Long businessId, String phoneNumber, String linkTarget);

    /** Batch form of {@link #existsByBusinessIdAndPhoneNumberAndLinkTargetAndClickedAtIsNotNull} —
     * one query for every row on the manager conversation view's list page, not one query per
     * row. See {@code BlockedNumberRepository#findByPhoneNumberIn}'s own doc comment for the same
     * pattern. */
    @Query("SELECT DISTINCT m.phoneNumber FROM SmsMessage m "
            + "WHERE m.businessId = :businessId AND m.phoneNumber IN :phoneNumbers "
            + "AND m.linkTarget = :linkTarget AND m.clickedAt IS NOT NULL")
    List<String> findPhoneNumbersWithClickedLinkTarget(@Param("businessId") Long businessId,
                                                        @Param("phoneNumbers") Collection<String> phoneNumbers,
                                                        @Param("linkTarget") String linkTarget);

    /** Whether this phone number has ever left a low-rating reply to the checkout-review-request
     * automation, for one business — permanently excludes them from the same-day-rebooking
     * win-back nudge (see {@code SameDayRebookingScheduler}). {@code phoneNumber} must already be
     * E.164-normalized. */
    boolean existsByBusinessIdAndPhoneNumberAndNegativeFeedbackAtIsNotNull(Long businessId, String phoneNumber);

    /** Batch form of "has any OUTBOUND message to this number ever come back with one of these
     * Twilio delivery-status error codes", for one business — same one-query-not-one-per-row
     * pattern as {@link #findPhoneNumbersWithClickedLinkTarget}. */
    @Query("SELECT DISTINCT m.phoneNumber FROM SmsMessage m "
            + "WHERE m.businessId = :businessId AND m.phoneNumber IN :phoneNumbers AND m.deliveryErrorCode IN :errorCodes")
    List<String> findPhoneNumbersWithDeliveryErrorCode(@Param("businessId") Long businessId,
                                                        @Param("phoneNumbers") Collection<String> phoneNumbers,
                                                        @Param("errorCodes") Collection<String> errorCodes);

    /** Most recent time this phone number was sent a click-tracked link to the given target, for
     * one business (any outbound message with that {@code link_target}, sent or not), or
     * {@code null} if never. See {@code SmsMessageLogService#linkEngagement}. */
    @Query("SELECT MAX(m.createdAt) FROM SmsMessage m "
            + "WHERE m.businessId = :businessId AND m.phoneNumber = :phoneNumber "
            + "AND m.linkTarget = :linkTarget AND m.direction = 'OUTBOUND'")
    Instant findLatestLinkSentAt(@Param("businessId") Long businessId, @Param("phoneNumber") String phoneNumber,
                                 @Param("linkTarget") String linkTarget);

    /** Most recent time this phone number actually clicked through a link to the given target, for
     * one business, or {@code null} if never — see {@code SmsMessageLogService#linkEngagement}. */
    @Query("SELECT MAX(m.clickedAt) FROM SmsMessage m "
            + "WHERE m.businessId = :businessId AND m.phoneNumber = :phoneNumber AND m.linkTarget = :linkTarget")
    Instant findLatestLinkClickedAt(@Param("businessId") Long businessId, @Param("phoneNumber") String phoneNumber,
                                    @Param("linkTarget") String linkTarget);

    /** Backs the hub's unread-count badge, for one business — every unread inbound message,
     * regardless of whether it ever matched an automation. */
    long countByBusinessIdAndDirectionAndReadAtIsNull(Long businessId, String direction);

    /** Marks every unread inbound message in one phone number's thread read in a single write, for
     * one business — backs the manager conversation view's "opening a thread marks it read"
     * behavior. Bulk, not a loop of the single-message endpoint. */
    @Modifying
    @Query("UPDATE SmsMessage m SET m.readAt = :now "
            + "WHERE m.businessId = :businessId AND m.phoneNumber = :phoneNumber "
            + "AND m.direction = 'INBOUND' AND m.readAt IS NULL")
    void markThreadRead(@Param("businessId") Long businessId, @Param("phoneNumber") String phoneNumber,
                        @Param("now") Instant now);

    /** "Mark as unread", for one business (see SmsMessageLogService#markThreadUnread) — un-reads
     * only the most recent inbound message in the thread. Native, not JPQL: HQL doesn't support a
     * correlated ORDER BY ... LIMIT 1 subquery, which Postgres does. */
    @Modifying
    @Query(value = "UPDATE sms_message SET read_at = NULL WHERE id = ("
            + "  SELECT id FROM sms_message WHERE business_id = :businessId AND phone_number = :phoneNumber"
            + "  AND direction = 'INBOUND' ORDER BY created_at DESC LIMIT 1)", nativeQuery = true)
    void markLastInboundUnread(@Param("businessId") Long businessId, @Param("phoneNumber") String phoneNumber);

    /** 30-day "sent count" shown per automation card, for one business — real sends only, not
     * blocked attempts. */
    long countByBusinessIdAndAutomationKeyAndDirectionAndStatusAndCreatedAtAfter(
            Long businessId, String automationKey, String direction, String status, Instant since);

    /** Same as above, further narrowed to one or more templates — see the original (pre-scoping)
     * doc comment on why this exists (avoids double-counting checkout_review_request's branch
     * reply). Plural because an automation can fire under more than one template key (e.g.
     * checkout_review_request's rating request picks between a with-technician / no-technician
     * variant — see SmsMessageTemplateCatalog). */
    long countByBusinessIdAndAutomationKeyAndTemplateKeyInAndDirectionAndStatusAndCreatedAtAfter(
            Long businessId, String automationKey, java.util.Collection<String> templateKeys, String direction,
            String status, Instant since);

    /** How many of an automation's 30-day sends carried a click-tracked link at all, for one
     * business — the denominator for the automation card's click-through rate. */
    long countByBusinessIdAndAutomationKeyAndDirectionAndStatusAndLinkTargetIsNotNullAndCreatedAtAfter(
            Long businessId, String automationKey, String direction, String status, Instant since);

    /** The numerator for the same click-through rate, for one business — link-carrying sends that
     * were actually clicked. */
    long countByBusinessIdAndAutomationKeyAndDirectionAndStatusAndLinkTargetIsNotNullAndClickedAtIsNotNullAndCreatedAtAfter(
            Long businessId, String automationKey, String direction, String status, Instant since);

    /** 30-day inbound-reply count for an automation, for one business — only meaningful for
     * automations that open an {@code SmsReplyFlow}. */
    long countByBusinessIdAndAutomationKeyAndDirectionAndCreatedAtAfter(
            Long businessId, String automationKey, String direction, Instant since);

    /** All-time successful-send count of one exact template to one phone number, for one business
     * — used by {@code SmsMessageTemplateService} to deterministically rotate a multi-variant
     * template's wording (count mod variant count) so the same regular customer sees a different
     * body each time instead of an identical one every visit. {@code phoneNumber} must already be
     * E.164-normalized. */
    long countByBusinessIdAndPhoneNumberAndTemplateKeyAndDirectionAndStatus(
            Long businessId, String phoneNumber, String templateKey, String direction, String status);

    /** Newest unread inbound rows first, for one business — the inbox view's default sort. */
    List<SmsMessage> findByBusinessIdAndDirectionAndReadAtIsNullOrderByCreatedAtDesc(Long businessId, String direction);

    // Parameters are explicitly CAST to string — a bare `:phoneNumber` inside CONCAT()/LIKE, when
    // bound null (the common case: no filter applied), leaves Postgres unable to infer its type
    // and it defaults to bytea, which then fails "operator does not exist: text ~~ bytea" against
    // the LIKE operator. The cast fixes the type for both the null-check and the LIKE branch.
    @Query("""
            SELECT m FROM SmsMessage m
            WHERE m.businessId = :businessId
              AND (:phoneNumber IS NULL OR m.phoneNumber LIKE CONCAT('%', CAST(:phoneNumber AS string), '%'))
              AND (:direction IS NULL OR m.direction = CAST(:direction AS string))
              AND (:automationKey IS NULL OR m.automationKey = CAST(:automationKey AS string))
            ORDER BY m.createdAt DESC
            """)
    Page<SmsMessage> search(@Param("businessId") Long businessId,
                             @Param("phoneNumber") String phoneNumber,
                             @Param("direction") String direction,
                             @Param("automationKey") String automationKey,
                             Pageable pageable);

    /** Newest matching message first, across every phone number for one business — backs the
     * manager conversation view's search box for matches buried in a thread's older history. */
    @Query("SELECT m FROM SmsMessage m WHERE m.businessId = :businessId "
            + "AND LOWER(m.body) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')) "
            + "ORDER BY m.createdAt DESC")
    List<SmsMessage> searchByBodyContaining(@Param("businessId") Long businessId, @Param("q") String q, Pageable pageable);

    /** Every reply to the checkout-review-request rating request, for one business — the
     * {@code /owner/reviews} dashboard's full review list (see V120). Not filtered to rows with a
     * {@link SmsMessage#getReplyFlowId()}/{@link SmsMessage#getRating()} set — a reply with
     * neither (no digit in the text, or predating V120's backfill) is still a real review, just
     * one with no attributable provider/rating. */
    List<SmsMessage> findByBusinessIdAndAutomationKeyAndDirectionOrderByCreatedAtDesc(
            Long businessId, String automationKey, String direction);

    /** Rows V120's one-time startup backfill still needs to link back to the flow they replied to
     * (and parse a rating from) — see {@code CheckoutReviewProviderRatingBackfillStartup}. */
    List<SmsMessage> findByBusinessIdAndAutomationKeyAndDirectionAndReplyFlowIdIsNull(
            Long businessId, String automationKey, String direction);
}
