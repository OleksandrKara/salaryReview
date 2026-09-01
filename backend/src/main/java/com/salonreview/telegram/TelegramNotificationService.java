package com.salonreview.telegram;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salonreview.domain.TelegramNotificationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
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
    private final String publicBaseUrl;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public TelegramNotificationService(TelegramConfigService configService,
                                        @Value("${app.public-base-url}") String publicBaseUrl) {
        this.configService = configService;
        this.publicBaseUrl = publicBaseUrl;
    }

    /** Returns {@code true} only on a confirmed successful send — never throws. */
    public boolean sendFourHandRequestAlert(FourHandRequestNotification n) {
        TelegramNotificationConfig cfg = configService.getForAutomation();
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

    /** Alerts managers/owner (same Telegram chat as the 4-hand alert — see
     * openspec/changes/sms-automations-hub) whenever a customer texts the salon's number, whether
     * or not it matched a pending automation reply — a customer reply always needs a human's
     * attention, not just a dashboard entry nobody's watching. {@code customerName} is
     * best-effort (resolved by the caller — see {@code TwilioInboundSmsController}), null when no
     * name could be resolved for this phone number; the alert falls back to just the formatted
     * phone number as its header in that case. Includes a tappable deep link straight into that
     * customer's thread on {@code /admin/messages} (see MessagesView's {@code ?phone=} handling),
     * so reading the alert and replying is one tap, not "open the app, find the right
     * conversation." Never throws, same contract as {@link #sendFourHandRequestAlert}. */
    public boolean sendInboundSmsAlert(String phoneNumber, String customerName, String body, String automationKey) {
        TelegramNotificationConfig cfg = configService.getForAutomation();
        String token = cfg.getBotToken();
        String chatId = cfg.getChatId();
        if (token == null || token.isBlank() || chatId == null || chatId.isBlank()) {
            log.info("Inbound-SMS Telegram alert skipped — bot token or chat id not configured");
            return false;
        }

        try {
            Map<String, Object> reqBody = Map.of("chat_id", chatId, "text", formatInboundSmsAlert(phoneNumber, customerName, body, automationKey),
                    "parse_mode", "HTML", "disable_web_page_preview", true);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.telegram.org/bot" + token + "/sendMessage"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(reqBody)))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                log.warn("Inbound-SMS Telegram alert send failed: HTTP {} {}", res.statusCode(), res.body());
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("Inbound-SMS Telegram alert send failed (caller unaffected): {}", e.getMessage());
            return false;
        }
    }

    /** Package-private for direct unit testing, same convention as {@link #formatMessage}. Telegram's
     * HTML parse mode (see the {@code sendMessage} call above) is what makes the name bold and the
     * link actually tappable — every piece of caller-supplied text is HTML-escaped first so a stray
     * {@code <}/{@code &} in a customer's own message can't break the formatting or, worse, get
     * silently swallowed by Telegram's parser. */
    String formatInboundSmsAlert(String phoneNumber, String customerName, String body, String automationKey) {
        String displayPhone = formatPhoneDisplay(phoneNumber);
        StringBuilder sb = new StringBuilder("📩 <b>New message from ")
                .append(escapeHtml(customerName != null && !customerName.isBlank() ? customerName : displayPhone))
                .append("</b>\n");
        if (customerName != null && !customerName.isBlank()) {
            sb.append("📱 ").append(escapeHtml(displayPhone)).append('\n');
        }
        if (automationKey != null && !automationKey.isBlank()) {
            sb.append("↩️ Reply to: ").append(escapeHtml(automationKey.replace('_', ' '))).append('\n');
        }
        sb.append("\n“").append(escapeHtml(body)).append("”\n\n");
        sb.append("<a href=\"").append(escapeHtml(chatLink(phoneNumber))).append("\">💬 Open chat</a>");
        return sb.toString();
    }

    /** The salon's own admin inbox, deep-linked straight to this customer's thread — see
     * MessagesView's {@code ?phone=} handling on the frontend. Package-private for direct unit
     * testing. */
    String chatLink(String phoneNumber) {
        return publicBaseUrl + "/admin/messages?phone=" + URLEncoder.encode(phoneNumber, StandardCharsets.UTF_8);
    }

    /** US-formatted "(858) 555-0100" for readability — falls back to the raw value for anything
     * that isn't a plain 10/11-digit US number (a short code, an already-odd value) rather than
     * mangling it. Package-private for direct unit testing. */
    static String formatPhoneDisplay(String phoneNumber) {
        if (phoneNumber == null) return "";
        String digits = phoneNumber.replaceAll("[^0-9]", "");
        if (digits.length() == 11 && digits.startsWith("1")) digits = digits.substring(1);
        if (digits.length() != 10) return phoneNumber;
        return "(" + digits.substring(0, 3) + ") " + digits.substring(3, 6) + "-" + digits.substring(6);
    }

    /** The handful of characters Telegram's HTML parse mode treats specially — see
     * https://core.telegram.org/bots/api#html-style. Package-private for direct unit testing. */
    static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Alerts staff that a customer just booked under the same-day-rebooking promo (see
     * openspec/changes/same-day-rebooking-discount design.md D7) — the customer is now
     * auto-enrolled in the Square discount group, but staff still need to know NOT to also apply
     * the old manual "Same day rebooking discount" (would stack to $20 off). Never throws, same
     * contract as {@link #sendFourHandRequestAlert}. */
    public boolean sendRebookingPromoAlert(String customerName, String phoneNumber, String appointmentStartAt) {
        TelegramNotificationConfig cfg = configService.getForAutomation();
        String token = cfg.getBotToken();
        String chatId = cfg.getChatId();
        if (token == null || token.isBlank() || chatId == null || chatId.isBlank()) {
            log.info("Rebooking-promo Telegram alert skipped — bot token or chat id not configured");
            return false;
        }

        String text = "🎁 Same-day rebooking discount booked\n"
                + "Name: " + (customerName == null ? "—" : customerName) + '\n'
                + "Phone: " + (phoneNumber == null ? "—" : phoneNumber) + '\n'
                + "Appointment: " + formatPreferredTime(appointmentStartAt) + '\n'
                + "⚠️ Auto-discount ($10) already applies at checkout — do NOT also apply the "
                + "manual 'Same day rebooking discount' or they'll get $20 off, not $10.";
        try {
            Map<String, Object> reqBody = Map.of("chat_id", chatId, "text", text);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.telegram.org/bot" + token + "/sendMessage"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(reqBody)))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                log.warn("Rebooking-promo Telegram alert send failed: HTTP {} {}", res.statusCode(), res.body());
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("Rebooking-promo Telegram alert send failed (caller unaffected): {}", e.getMessage());
            return false;
        }
    }

    /** Alerts the business's staff Telegram channel when a customer's booking lands very close to
     * its own start time — see {@code SameDayBookingAlertService}, which decides what counts as
     * "very close" and resolves {@code providerNames}/{@code customerName} before calling this.
     * Business-scoped via {@code businessId} (not {@link #sendFourHandRequestAlert} and friends'
     * always-{@code legacySmsBusiness()} shortcut) — this fires from a real per-business webhook,
     * so it must never alert business A's channel about business B's booking. Never throws, same
     * contract as every other send method here. */
    public boolean sendSameDayBookingAlert(Long businessId, String providerNames, String customerName,
                                            String appointmentStartAt, Duration leadTime) {
        TelegramNotificationConfig cfg = configService.get(businessId);
        String token = cfg.getBotToken();
        String chatId = cfg.getChatId();
        if (token == null || token.isBlank() || chatId == null || chatId.isBlank()) {
            log.info("Same-day-booking Telegram alert skipped — bot token or chat id not configured for business {}", businessId);
            return false;
        }

        String text = "⏰ Last-minute booking — " + formatLeadTime(leadTime) + " notice\n"
                + "Provider: " + providerNames + '\n'
                + "Client: " + (customerName == null ? "—" : customerName) + '\n'
                + "Appointment: " + formatPreferredTime(appointmentStartAt);
        try {
            Map<String, Object> reqBody = Map.of("chat_id", chatId, "text", text);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.telegram.org/bot" + token + "/sendMessage"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(reqBody)))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                log.warn("Same-day-booking Telegram alert send failed: HTTP {} {}", res.statusCode(), res.body());
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("Same-day-booking Telegram alert send failed (caller unaffected): {}", e.getMessage());
            return false;
        }
    }

    /** "45 min" under an hour, "3h" or "3h 20m" at/past one — matches how a person would actually
     * say it, not a raw minute count. Package-private for direct unit testing. */
    static String formatLeadTime(Duration d) {
        long totalMinutes = Math.max(0, d.toMinutes());
        if (totalMinutes < 60) return totalMinutes + " min";
        long hours = totalMinutes / 60;
        long mins = totalMinutes % 60;
        return mins == 0 ? hours + "h" : hours + "h " + mins + "m";
    }

    /** Package-private for direct unit testing, same convention as {@link #formatPreferredTime}. */
    String formatMessage(FourHandRequestNotification n) {
        StringBuilder sb = new StringBuilder();
        sb.append("🙌 New 4-Hand request (").append(n.source()).append(")\n");
        sb.append("Name: ").append(n.customerName()).append('\n');
        sb.append("Phone: ").append(n.phoneNumber()).append('\n');
        sb.append("Requested: ").append(n.requestedServices() == null ? "—" : n.requestedServices()).append('\n');
        sb.append("Preferred time: ").append(formatPreferredTime(n.preferredStartAt()));
        if (n.estimatedPrice() != null) {
            sb.append("\nEstimated price: ").append(formatEstimatedPrice(n.estimatedPrice()));
        }
        if (n.note() != null && !n.note().isBlank()) {
            sb.append("\nNote: ").append(n.note());
        }
        return sb.toString();
    }

    /** Whole-dollar display like the rest of the site's $299/$254 4-hand pricing — never has cents
     * in practice, but formats them if a future caller ever sends a fractional value. */
    private static String formatEstimatedPrice(double dollars) {
        return dollars == Math.floor(dollars) ? String.format("$%.0f", dollars) : String.format("$%.2f", dollars);
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
