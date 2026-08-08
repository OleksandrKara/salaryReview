package com.salonreview.repo;

import com.salonreview.domain.SmsMessageReaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SmsMessageReactionRepository extends JpaRepository<SmsMessageReaction, Long> {

    /** The natural key from V70's unique index — used by {@code SmsReactionService}'s upsert (a
     * re-tap or a changed staff reaction updates this row instead of creating a duplicate). */
    Optional<SmsMessageReaction> findBySmsMessageIdAndSourceAndReactor(Long smsMessageId, String source, String reactor);

    /** Batch form for a loaded thread page — one query for every message row, not one per message,
     * same pattern as {@code SmsMediaService#mediaForMessages}. */
    @Query("SELECT r FROM SmsMessageReaction r WHERE r.smsMessageId IN :smsMessageIds ORDER BY r.id ASC")
    List<SmsMessageReaction> findBySmsMessageIdIn(@Param("smsMessageIds") Collection<Long> smsMessageIds);
}
