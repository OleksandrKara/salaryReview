package com.salonreview.repo;

import com.salonreview.domain.SmsMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
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
    }

    @Query(value = """
            SELECT latest.phone_number AS phoneNumber,
                   latest.last_message_at AS lastMessageAt,
                   latest.last_message_body AS lastMessageBody,
                   latest.last_message_direction AS lastMessageDirection,
                   COALESCE(unread.unread_count, 0) AS unreadCount
            FROM (
                SELECT DISTINCT ON (phone_number) phone_number,
                       created_at AS last_message_at,
                       body AS last_message_body,
                       direction AS last_message_direction
                FROM sms_message
                ORDER BY phone_number, created_at DESC
            ) latest
            LEFT JOIN (
                SELECT phone_number, COUNT(*) AS unread_count
                FROM sms_message
                WHERE direction = 'INBOUND' AND read_at IS NULL
                GROUP BY phone_number
            ) unread ON unread.phone_number = latest.phone_number
            ORDER BY latest.last_message_at DESC
            """, nativeQuery = true)
    List<ConversationSummaryProjection> conversationSummaries();

    /** Full chronological thread for one phone number — backs the manager conversation view's
     * selected-thread panel. */
    List<SmsMessage> findByPhoneNumberOrderByCreatedAtAsc(String phoneNumber);

    /** Backs the click-tracked {@code /r/{token}} short link — see V53, design.md D6. */
    Optional<SmsMessage> findByClickToken(String clickToken);

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

    /** 30-day "sent count" shown per automation card — real sends only, not blocked attempts. */
    long countByAutomationKeyAndDirectionAndStatusAndCreatedAtAfter(
            String automationKey, String direction, String status, Instant since);

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
