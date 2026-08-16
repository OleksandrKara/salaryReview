package com.salonreview.sms;

import com.salonreview.domain.SmsMessageMedia;
import com.salonreview.domain.TwilioSmsConfig;
import com.salonreview.repo.SmsMessageMediaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MMS photo attachments — storage (BYTEA, see V69/{@link SmsMessageMedia}) and inbound-webhook
 * ingestion. Outbound attachments are stored by {@link TwilioSmsService#sendManualWithMedia} using
 * the same {@link #store} method, ahead of the actual Twilio send — the reserve-then-finalize
 * pattern needs each media row's public URL to exist before Twilio can be asked to fetch it (same
 * reasoning as {@code CheckoutReviewReplyService}'s click-token reservation).
 */
@Service
public class SmsMediaService {

    private static final Logger log = LoggerFactory.getLogger(SmsMediaService.class);

    private final SmsMessageMediaRepository repository;
    private final TwilioSmsConfigService configService;
    private final TwilioSmsClient client;
    private final String publicBaseUrl;

    public SmsMediaService(SmsMessageMediaRepository repository, TwilioSmsConfigService configService,
                           TwilioSmsClient client, @Value("${app.public-base-url}") String publicBaseUrl) {
        this.repository = repository;
        this.configService = configService;
        this.client = client;
        this.publicBaseUrl = publicBaseUrl;
    }

    public record MediaInfo(String url, String contentType) {}

    /** A fresh {@link ClickTokens#generate()} candidate, re-rolled on the rare chance of a
     * collision — same convention as {@code SmsMessageLogService#generateUniqueClickToken}. */
    private String generateUniqueAccessToken() {
        for (int attempt = 0; attempt < 20; attempt++) {
            String candidate = ClickTokens.generate();
            if (!repository.existsByAccessToken(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not generate a unique media access token after 20 attempts");
    }

    public SmsMessageMedia store(Long smsMessageId, String contentType, byte[] fileData) {
        return repository.save(SmsMessageMedia.builder()
                .smsMessageId(smsMessageId)
                .contentType(contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType)
                .fileData(fileData)
                .accessToken(generateUniqueAccessToken())
                .build());
    }

    public Optional<SmsMessageMedia> get(String accessToken) {
        return repository.findByAccessToken(accessToken);
    }

    public String publicUrl(SmsMessageMedia media) {
        return publicBaseUrl + "/api/public/sms-media/" + media.getAccessToken();
    }

    /** Batch form for a loaded thread page — one query for every message row, not one per
     * message, same pattern as {@code SmsMessageLogService#phoneNumbersWithClickedLinkTarget}. */
    public Map<Long, List<MediaInfo>> mediaForMessages(Collection<Long> smsMessageIds) {
        if (smsMessageIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<MediaInfo>> result = new LinkedHashMap<>();
        for (SmsMessageMedia m : repository.findBySmsMessageIdIn(smsMessageIds)) {
            result.computeIfAbsent(m.getSmsMessageId(), k -> new ArrayList<>()).add(new MediaInfo(publicUrl(m), m.getContentType()));
        }
        return result;
    }

    /** Downloads and stores every attachment on Twilio's inbound webhook payload ({@code NumMedia}/
     * {@code MediaUrl{n}}/{@code MediaContentType{n}}) — best-effort per attachment: one failed
     * fetch doesn't lose the others, and nothing here ever throws back into the caller (see
     * {@code TwilioInboundSmsController}, which already logs the message row unconditionally
     * before this runs — a photo that fails to download still leaves the text/thread intact). */
    public void ingestInboundMedia(long smsMessageId, Map<String, String> params) {
        int numMedia = parseNumMedia(params.get("NumMedia"));
        if (numMedia == 0) {
            return;
        }
        TwilioSmsConfig config = configService.getForAutomation();
        if (!config.isConfigured()) {
            log.warn("Inbound MMS with {} attachment(s) on message {} skipped — Twilio credentials not configured",
                    numMedia, smsMessageId);
            return;
        }
        for (int i = 0; i < numMedia; i++) {
            String mediaUrl = params.get("MediaUrl" + i);
            if (mediaUrl == null || mediaUrl.isBlank()) {
                continue;
            }
            String contentType = params.get("MediaContentType" + i);
            try {
                byte[] data = client.fetchMedia(config, mediaUrl);
                store(smsMessageId, contentType, data);
            } catch (Exception e) {
                log.warn("Failed to fetch inbound MMS attachment {} for message {}: {}", i, smsMessageId, e.getMessage());
            }
        }
    }

    private static int parseNumMedia(String raw) {
        try {
            return raw == null ? 0 : Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
