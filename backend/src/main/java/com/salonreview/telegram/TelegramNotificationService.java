package com.salonreview.telegram;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salonreview.domain.TelegramNotificationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/**
 * Sends the 4-hand-request Telegram alert on behalf of mani/akluxnails-home, which never see the
 * bot token themselves (see {@link com.salonreview.web.InternalNotificationController}). Hand-rolled
 * HTTP client like {@link com.salonreview.rag.VoyageClient} rather than pulling in a framework.
 *
 * <p>Unlike Voyage's embedding calls, this never throws: a missing/invalid token, blank chat_id,
 * or a Telegram-side outage must never break lead capture in the calling app — it just means the
 * alert didn't go out, logged for visibility.
 */
@Service
public class TelegramNotificationService {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotificationService.class);

    // The salon (San Diego, CA) is Pacific Time — mani/akluxnails-home both send preferredStartAt
    // as UTC ISO 8601 (see their DateTimeStep pickers, which are Pacific-labeled but query/submit
    // in UTC), so it must be converted here rather than shown raw.
    private static final DateTimeFormatter PACIFIC_TIME_FORMATTER = DateTimeFormatter
            .ofPattern("EEE, MMM d, yyyy 'at' h:mm a zzz", Locale.US)
            .withZone(ZoneId.of("America/Los_Angeles"));

    private final TelegramConfigService configService;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public TelegramNotificationService(TelegramConfigService configService) {
        this.configService = configService;
    }

    /** Returns {@code true} only on a confirmed successful send — never throws. */
    public boolean sendFourHandRequestAlert(FourHandRequestNotification n) {
        TelegramNotificationConfig cfg = configService.get();
        String token = cfg.getBotToken();
        String chatId = cfg.getChatId();
        if (token == null || token.isBlank() || chatId == null || chatId.isBlank()) {
            log.info("4-hand Telegram alert skipped — bot token or chat id not configured");
            return false;
        }

        try {
            Map<String, Object> body = Map.of("chat_id", chatId, "text", formatMessage(n));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.telegram.org/bot" + token + "/sendMessage"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                log.warn("4-hand Telegram alert send failed: HTTP {} {}", res.statusCode(), res.body());
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("4-hand Telegram alert send failed (caller unaffected): {}", e.getMessage());
            return false;
        }
    }

    private String formatMessage(FourHandRequestNotification n) {
        StringBuilder sb = new StringBuilder();
        sb.append("🙌 New 4-Hand request (").append(n.source()).append(")\n");
        sb.append("Name: ").append(n.customerName()).append('\n');
        sb.append("Phone: ").append(n.phoneNumber()).append('\n');
        sb.append("Requested: ").append(n.requestedServices() == null ? "—" : n.requestedServices()).append('\n');
        sb.append("Preferred time: ").append(formatPreferredTime(n.preferredStartAt()));
        if (n.note() != null && !n.note().isBlank()) {
            sb.append("\nNote: ").append(n.note());
        }
        return sb.toString();
    }

    /** Best-effort — falls back to the raw value rather than fail the whole alert over a
     * malformed timestamp from a caller. Package-private for direct unit testing. */
    static String formatPreferredTime(String isoStartAt) {
        try {
            return PACIFIC_TIME_FORMATTER.format(Instant.parse(isoStartAt));
        } catch (Exception e) {
            return isoStartAt;
        }
    }
}
