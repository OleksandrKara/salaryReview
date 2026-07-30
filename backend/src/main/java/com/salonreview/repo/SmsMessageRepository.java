package com.salonreview.repo;

import com.salonreview.domain.SmsMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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

    /** Backs the hub's unread-count badge — every unread inbound message, regardless of whether
     * it ever matched an automation. */
    long countByDirectionAndReadAtIsNull(String direction);

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
}
