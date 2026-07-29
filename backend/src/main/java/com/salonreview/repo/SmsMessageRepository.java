package com.salonreview.repo;

import com.salonreview.domain.SmsMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface SmsMessageRepository extends JpaRepository<SmsMessage, Long> {

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
