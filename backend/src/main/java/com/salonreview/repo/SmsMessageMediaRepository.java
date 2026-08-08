package com.salonreview.repo;

import com.salonreview.domain.SmsMessageMedia;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SmsMessageMediaRepository extends JpaRepository<SmsMessageMedia, Long> {

    /** Backs the public {@code /api/public/sms-media/{token}} serving endpoint. */
    Optional<SmsMessageMedia> findByAccessToken(String accessToken);

    /** Used to re-roll a freshly generated {@link com.salonreview.sms.ClickTokens} candidate on
     * the rare chance it collides with one already in use — same convention as
     * {@link SmsMessageRepository#existsByClickToken}. */
    boolean existsByAccessToken(String accessToken);

    /** All media for one message, in upload order — a message can carry more than one photo. */
    List<SmsMessageMedia> findBySmsMessageIdOrderByIdAsc(Long smsMessageId);

    /** Batch form of {@link #findBySmsMessageIdOrderByIdAsc} — one query for every row on a
     * loaded thread page, not one per message, same pattern as
     * {@code SmsMessageRepository#findPhoneNumbersWithClickedLinkTarget}. */
    @Query("SELECT m FROM SmsMessageMedia m WHERE m.smsMessageId IN :smsMessageIds ORDER BY m.id ASC")
    List<SmsMessageMedia> findBySmsMessageIdIn(@Param("smsMessageIds") Collection<Long> smsMessageIds);
}
