package com.salonreview.telegram;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salonreview.domain.TelegramNotificationConfig;
import com.salonreview.repo.BusinessRepository;
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
    private final BusinessRepository businesses;
    private final String publicBaseUrl;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public TelegramNotificationService(TelegramConfigService configService, BusinessRepository businesses,
                                        @Value("${app.public-base-url}") String publicBaseUrl) {
        this.configService = configService;
        this.businesses = businesses;
        this.publicBaseUrl = publicBaseUrl;
    }

    /** "🏢 &lt;business name&gt;\n" prefix — both businesses currently share one staff Telegram
     * chat (same bot, same chat id), so without this a message has no reliable way to say which
     * business it's about (2026-09-16 owner request). Empty string — no prefix, no extra line —
     * if the business can't be resolved; a missing label is cosmetic, never a reason to fail the
     * whole alert. Package-private for direct unit testing, same convention as {@link #formatMessage}. */
    String businessLabel(Long businessId) {
        // escapeHtml is a no-op for both real business names today (neither contains &/</>), but
        // costs nothing and keeps this safe for the one send method (sendInboundSmsAlert) that
        // actually uses Telegram's HTML parse_mode — the other send methods here are plain text,
        // where escaping is equally harmless.
        return businesses.findById(businessId).map(b -> "🏢 " + escapeHtml(b.getName()) + "\n").orElse("");
    }

    /** Returns {@code true} only on a confirmed successful send — never throws. */
    public boolean sendFourHandRequestAlert(Long businessId, FourHandRequestNotification n) {
        TelegramNotificationConfig cfg = configService.get(businessId);
        String token = cfg.getBotToken();
        String chatId = cfg.getChatId();
        if (token == null || token.isBlank() || chatId == null || chatId.isBlank()) {
            log.info("4-hand Telegram alert skipped — bot token or chat id not configured");
            return false;
        }

        try {
            Map<String, Object> body = Map.of("chat_id", chatId, "text", businessLabel(businessId) + formatMessage(n));
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
    public boolean sendInboundSmsAlert(Long businessId, String phoneNumber, String customerName, String body, String automationKey) {
        return sendInboundSmsAlert(businessId, phoneNumber, customerName, body, automationKey, null);
    }

    /** Same alert, plus an optional line saying the text came from the website's Text button
     * (page and traffic source, see SmsWebsiteLeadService); null for any other text. */
    public boolean sendInboundSmsAlert(Long businessId, String phoneNumber, String customerName, String body,
                                       String automationKey, String websiteContext) {
        TelegramNotificationConfig cfg = configService.get(businessId);
        String token = cfg.getBotToken();
        String chatId = cfg.getChatId();
        if (token == null || token.isBlank() || chatId == null || chatId.isBlank()) {
            log.info("Inbound-SMS Telegram alert skipped — bot token or chat id not configured");
            return false;
        }

        try {
            Map<String, Object> reqBody = Map.of("chat_id", chatId,
                    "text", businessLabel(businessId) + formatInboundSmsAlert(phoneNumber, customerName, body, automationKey, websiteContext),
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
        return formatInboundSmsAlert(phoneNumber, customerName, body, automationKey, null);
    }

    String formatInboundSmsAlert(String phoneNumber, String customerName, String body, String automationKey,
                                 String websiteContext) {
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
        if (websiteContext != null && !websiteContext.isBlank()) {
            sb.append(escapeHtml(websiteContext)).append('\n');
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
    public boolean sendRebookingPromoAlert(Long businessId, String customerName, String phoneNumber, String appointmentStartAt) {
        TelegramNotificationConfig cfg = configService.get(businessId);
        String token = cfg.getBotToken();
        String chatId = cfg.getChatId();
        if (token == null || token.isBlank() || chatId == null || chatId.isBlank()) {
            log.info("Rebooking-promo Telegram alert skipped — bot token or chat id not configured");
            return false;
        }

        String text = businessLabel(businessId) + "🎁 Same-day rebooking discount booked\n"
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

        String text = businessLabel(businessId) + formatSameDayBookingMessage(providerNames, customerName, appointmentStartAt, leadTime);
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

    /** English on top, a Russian translation below a divider — the salon's manager (the one who
     * actually watches this shared staff channel, see {@code SameDayBookingAlertService}) reads
     * Russian day to day, and the message is an instruction to *them*, not just a fact dump: check
     * the provider actually saw it, and take the client over personally if the provider can't make
     * it. Deliberately avoids gendered pronouns in English (a provider's gender isn't stored
     * anywhere in this codebase) — Russian handles the same ambiguity with the informal "видел(а)"/
     * "недоступен(на)" construction rather than guessing one gender. Package-private for direct
     * unit testing, same convention as {@link #formatLeadTime}/{@link #formatMessage}. */
    static String formatSameDayBookingMessage(String providerNames, String customerName,
                                                String appointmentStartAt, Duration leadTime) {
        String lead = formatLeadTime(leadTime);
        String client = customerName == null ? "—" : customerName;
        String when = formatPreferredTime(appointmentStartAt);
        boolean multipleProviders = providerNames.contains(",");

        String en = "⚠️ Last-minute booking — only " + lead + " notice\n"
                + "👤 Client: " + client + "\n"
                + "💇 Provider: " + providerNames + "\n"
                + "🕐 Appointment: " + when + "\n\n"
                + "Please make sure " + providerNames + " " + (multipleProviders ? "have" : "has")
                + " seen this. If unavailable, please reach out to " + client + " and handle it yourself.";

        String ru = "⚠️ Срочная запись — предупреждение всего " + lead + "\n"
                + "👤 Клиент: " + client + "\n"
                + "💇 Мастер: " + providerNames + "\n"
                + "🕐 Запись: " + when + "\n\n"
                + "Пожалуйста, убедитесь, что " + providerNames + " видел(а) это сообщение. "
                + "Если недоступен(на) — свяжитесь с " + client + " и обработайте лично.";

        return en + "\n\n—\n\n" + ru;
    }

    /** Alerts a business's staff Telegram channel the moment a customer's card fails to charge —
     * a genuine decline (their card/bank said no) or a failure on our own side (Square API error,
     * bad request, etc.), distinguished by {@code n.clientError()} so staff know whether to expect
     * the customer to just retry themselves or whether something needs actual investigation.
     * Business-scoped via {@code businessId} (resolved by the caller — see
     * {@code InternalNotificationController#resolveBusiness}), same pattern as
     * {@link #sendSameDayBookingAlert}. Never throws, same contract as every other send method
     * here. */
    public boolean sendPaymentFailedAlert(Long businessId, PaymentFailedNotification n) {
        TelegramNotificationConfig cfg = configService.get(businessId);
        String token = cfg.getBotToken();
        String chatId = cfg.getChatId();
        if (token == null || token.isBlank() || chatId == null || chatId.isBlank()) {
            log.info("Payment-failed Telegram alert skipped — bot token or chat id not configured for business {}", businessId);
            return false;
        }

        String text = businessLabel(businessId) + formatPaymentFailedMessage(n);
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
                log.warn("Payment-failed Telegram alert send failed: HTTP {} {}", res.statusCode(), res.body());
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("Payment-failed Telegram alert send failed (caller unaffected): {}", e.getMessage());
            return false;
        }
    }

    /** Card-on-file page alerts (akluxnails.com/card): a card saved (with any warning signs, such
     * as a prepaid card or a cardholder name that doesn't match), a card refused by Square/the bank,
     * or the page pausing itself after a burst of failures (possible card testing). Same shared
     * staff chat and never-throws contract as every other send method here. */
    public boolean sendCardOnFileAlert(Long businessId, CardOnFileNotification n) {
        TelegramNotificationConfig cfg = configService.get(businessId);
        String token = cfg.getBotToken();
        String chatId = cfg.getChatId();
        if (token == null || token.isBlank() || chatId == null || chatId.isBlank()) {
            log.info("Card-on-file Telegram alert skipped: bot token or chat id not configured for business {}", businessId);
            return false;
        }
        String text = businessLabel(businessId) + formatCardOnFileMessage(n);
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
                log.warn("Card-on-file Telegram alert send failed: HTTP {} {}", res.statusCode(), res.body());
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("Card-on-file Telegram alert send failed (caller unaffected): {}", e.getMessage());
            return false;
        }
    }

    /** English on top, Russian below a divider, same as {@link #formatPaymentFailedMessage}.
     * Package-private for direct unit testing. */
    static String formatCardOnFileMessage(CardOnFileNotification n) {
        String client = blankToDash(n.customerName());
        String phone = blankToDash(n.phoneNumber());
        String email = n.email() == null || n.email().isBlank() ? "" : "📧 " + n.email() + "\n";
        String card = (n.cardBrand() == null ? "Card" : n.cardBrand()) + (n.last4() == null ? "" : " •••• " + n.last4())
                + (n.cardType() == null ? "" : ", " + n.cardType())
                + (n.expiry() == null ? "" : ", exp " + n.expiry());
        String attempts = n.failedAttempts() == null || n.failedAttempts() <= 0 ? "" : String.valueOf(n.failedAttempts());
        java.util.List<String> warnings = n.warnings() == null ? java.util.List.of() : n.warnings();
        String code = n.errorCode() == null || n.errorCode().isBlank() ? "" : " (" + n.errorCode() + ")";
        String reasonEn = blankToDash(languagePart(n.errorMessage(), false)) + code;
        String reasonRu = blankToDash(languagePart(n.errorMessage(), true)) + code;

        String en;
        String ru;
        switch (n.event()) {
            case SAVED -> {
                en = (warnings.isEmpty() ? "✅ Card on file added" : "⚠️ Card on file added, please check") + "\n"
                        + "👤 Client: " + client + "\n📱 Phone: " + phone + "\n" + email
                        + "💳 " + card
                        + (warnings.isEmpty() ? "" : "\n" + warnings.stream().map(w -> "⚠️ " + languagePart(w, false)).collect(java.util.stream.Collectors.joining("\n")));
                ru = (warnings.isEmpty() ? "✅ Клиент добавил карту" : "⚠️ Клиент добавил карту, проверьте") + "\n"
                        + "👤 Клиент: " + client + "\n📱 Телефон: " + phone + "\n" + email
                        + "💳 " + card
                        + (warnings.isEmpty() ? "" : "\n" + warnings.stream().map(w -> "⚠️ " + languagePart(w, true)).collect(java.util.stream.Collectors.joining("\n")));
            }
            case DECLINED -> {
                en = "❌ Card on file refused\n"
                        + "👤 Client: " + client + "\n📱 Phone: " + phone + "\n" + email
                        + "❌ Reason: " + reasonEn
                        + (attempts.isEmpty() ? "" : "\n🔁 Failed tries (last hour): " + attempts)
                        + "\n\nThe card was NOT saved. If they don't succeed, reach out before the appointment.";
                ru = "❌ Карта не принята\n"
                        + "👤 Клиент: " + client + "\n📱 Телефон: " + phone + "\n" + email
                        + "❌ Причина: " + reasonRu
                        + (attempts.isEmpty() ? "" : "\n🔁 Неудачных попыток за час: " + attempts)
                        + "\n\nКарта НЕ сохранена. Если клиент так и не добавит карту, свяжитесь с ним до записи.";
            }
            default -> {
                en = "🚨 Card page paused\n"
                        + "Too many refused cards in a short time" + (attempts.isEmpty() ? "" : " (" + attempts + ")")
                        + ". This looks like card testing by a bot, so akluxnails.com/card stopped accepting cards for an hour. "
                        + "Nothing was charged. Clients can still book; their card step in the booking flow is unaffected.";
                ru = "🚨 Страница карты на паузе\n"
                        + "Слишком много отклонённых карт за короткое время" + (attempts.isEmpty() ? "" : " (" + attempts + ")")
                        + ". Похоже на проверку украденных карт ботом, поэтому akluxnails.com/card не принимает карты час. "
                        + "Ничего не списано. Запись на сайте работает как обычно.";
            }
        }
        return en + "\n\n· · ·\n\n" + ru;
    }

    /** akluxnails-home sends reasons and warnings as "English / Russian"; each language section
     * shows only its own half. A string without the separator is shown as is in both. */
    static String languagePart(String s, boolean russian) {
        if (s == null) return null;
        int i = s.indexOf(" / ");
        if (i < 0) return s;
        return russian ? s.substring(i + 3).trim() : s.substring(0, i).trim();
    }

    private static String blankToDash(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }

    /** English on top, Russian below a divider — same reasoning and audience as
     * {@link #formatSameDayBookingMessage}. Package-private for direct unit testing. */
    static String formatPaymentFailedMessage(PaymentFailedNotification n) {
        String client = n.customerName() == null || n.customerName().isBlank() ? "—" : n.customerName();
        String phone = n.phoneNumber() == null || n.phoneNumber().isBlank() ? "—" : n.phoneNumber();
        String amount = n.amount() == null ? "—" : formatEstimatedPrice(n.amount());
        String reason = n.errorMessage() == null || n.errorMessage().isBlank() ? "Unknown error" : n.errorMessage();
        String reasonWithCode = n.errorCode() == null || n.errorCode().isBlank() ? reason : reason + " (" + n.errorCode() + ")";

        String en = "⚠️ Payment failed\n"
                + "👤 Client: " + client + "\n"
                + "📱 Phone: " + phone + "\n"
                + "💳 Attempted: " + amount + " for " + n.serviceName() + "\n"
                + "❌ Reason: " + reasonWithCode + "\n\n"
                + (n.clientError()
                        ? "This looks like a card/client-side issue — they may just need to try a different card."
                        : "⚠️ This looks like a problem on our side — please check Square / the booking system.");

        String ru = "⚠️ Ошибка оплаты\n"
                + "👤 Клиент: " + client + "\n"
                + "📱 Телефон: " + phone + "\n"
                + "💳 Попытка оплаты: " + amount + " за " + n.serviceName() + "\n"
                + "❌ Причина: " + reasonWithCode + "\n\n"
                + (n.clientError()
                        ? "Похоже, проблема на стороне карты/клиента — возможно, стоит попробовать другую карту."
                        : "⚠️ Похоже, проблема на нашей стороне — проверьте Square / систему записи.");

        return en + "\n\n—\n\n" + ru;
    }

    /** Alerts a business's staff Telegram channel the moment a customer books a free PMU
     * consultation — the {@code consultation_lead_sms} automation already text-confirms the
     * customer (see {@code SmsAutomationRegistry}), but until now staff had no visibility that a
     * booking had happened at all (2026-09-25 owner report: "нам приходило оповещение также помимо
     * СМС"). Same shared staff chat as every other business-2 alert here (payment-failed, same-day
     * booking, provider-schedule-closure). Business-scoped via {@code businessId}, same pattern as
     * {@link #sendPaymentFailedAlert}. Never throws, same contract as every other send method here. */
    public boolean sendConsultationRequestAlert(Long businessId, ConsultationRequestNotification n) {
        TelegramNotificationConfig cfg = configService.get(businessId);
        String token = cfg.getBotToken();
        String chatId = cfg.getChatId();
        if (token == null || token.isBlank() || chatId == null || chatId.isBlank()) {
            log.info("Consultation-request Telegram alert skipped — bot token or chat id not configured for business {}", businessId);
            return false;
        }

        String text = businessLabel(businessId) + formatConsultationRequestMessage(n);
        try {
            // No link preview: the message now carries the booking page's URL, and a big preview card
            // of our own page would push the actual lead details off screen.
            Map<String, Object> reqBody = Map.of("chat_id", chatId, "text", text, "disable_web_page_preview", true);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.telegram.org/bot" + token + "/sendMessage"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(reqBody)))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                log.warn("Consultation-request Telegram alert send failed: HTTP {} {}", res.statusCode(), res.body());
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("Consultation-request Telegram alert send failed (caller unaffected): {}", e.getMessage());
            return false;
        }
    }

    /** "Thinking it over" alert for the consultation_follow_up sequence (owner request 2026-10-06):
     * the day after a consultation with no procedure booked, staff see who is about to get the
     * artist's follow-up texts, with a URL button that stops the sequence for this client (not a
     * candidate, consultation didn't happen, she'll come back later). A URL button, not a callback
     * button: this bot has no update handler, and the link works from any staff member's phone.
     * Same staff chat and never-throws contract as every other send method here. */
    public boolean sendConsultationFollowUpAlert(Long businessId, String customerName, String artistName,
                                                 java.time.Instant consultationStart, String stopUrl) {
        TelegramNotificationConfig cfg = configService.get(businessId);
        String token = cfg.getBotToken();
        String chatId = cfg.getChatId();
        if (token == null || token.isBlank() || chatId == null || chatId.isBlank()) {
            log.info("Consultation follow-up Telegram alert skipped: bot token or chat id not configured for business {}", businessId);
            return false;
        }
        String text = businessLabel(businessId) + formatConsultationFollowUpMessage(customerName, artistName, consultationStart);
        try {
            Map<String, Object> reqBody = new java.util.HashMap<>(Map.of("chat_id", chatId, "text", text, "disable_web_page_preview", true));
            if (stopUrl != null) {
                Map<String, Object> button = Map.of("text", "🚫 Don't message this client / Не писать клиенту", "url", stopUrl);
                reqBody.put("reply_markup", Map.of("inline_keyboard", java.util.List.of(java.util.List.of(button))));
            }
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.telegram.org/bot" + token + "/sendMessage"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(reqBody)))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                log.warn("Consultation follow-up Telegram alert send failed: HTTP {} {}", res.statusCode(), res.body());
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("Consultation follow-up Telegram alert send failed (caller unaffected): {}", e.getMessage());
            return false;
        }
    }

    /** Package-private for direct unit testing. */
    static String formatConsultationFollowUpMessage(String customerName, String artistName, java.time.Instant consultationStart) {
        String client = customerName == null || customerName.isBlank() ? "Client" : customerName;
        String artist = artistName == null || artistName.isBlank() ? "the artist" : artistName;
        String artistRu = artistName == null || artistName.isBlank() ? "мастера" : artistName;
        String when = consultationStart == null ? "?" : formatPreferredTime(consultationStart.toString());
        String en = "💭 Thinking it over: " + client + "\n"
                + "Consultation with " + artist + " (" + when + "), no procedure booked yet.\n"
                + "Tomorrow they get a follow-up text from " + artist + ". Tap the button if they shouldn't get "
                + "messages: not a candidate, the consultation didn't happen, or they'll come back on their own.";
        String ru = "💭 Думает: " + client + "\n"
                + "Консультация с " + artist + " (" + when + "), на процедуру пока не записан(а).\n"
                + "Завтра клиенту уйдёт SMS от " + artistRu + ". Если писать не нужно (не подходит, консультация не "
                + "состоялась, клиент сам вернётся позже), нажмите кнопку.";
        return en + "\n\n-----\n\n" + ru;
    }

    /** English on top, Russian below a divider — same reasoning and audience as
     * {@link #formatSameDayBookingMessage}. {@code locationAddress} only ever renders (and is only
     * ever populated by the caller) for an in-person consultation — an online one just says
     * "Online (phone call)" with no address line, mirroring the SMS's own online/in-person
     * {@code detailsClause} branch (see salonLandings' {@code notify_consultation_request_sms}).
     * {@code artistName} (2026-09-28 owner request: "хочу видеть на какого мастера была сделана
     * консультация") renders as "—" when unresolved rather than omitting the line, same convention
     * as every other optional field here.
     * {@code sourcePageUrl}/{@code sourcePageTitle}/{@code adCampaign} (2026-09-30 owner request:
     * managers should see which page the booking came from, to guess what the client wants): the
     * page line always renders ("—" if unknown), the ad line only when the visit came from an ad.
     * The heading no longer says "free" for the in-person one: that consultation is paid ($50).
     * Package-private for direct unit testing. */
    static String formatConsultationRequestMessage(ConsultationRequestNotification n) {
        String client = n.customerName() == null || n.customerName().isBlank() ? "—" : n.customerName();
        String artist = n.artistName() == null || n.artistName().isBlank() ? "—" : n.artistName();
        String phone = n.phoneNumber() == null || n.phoneNumber().isBlank() ? "—" : n.phoneNumber();
        String when = formatPreferredTime(n.startAt());
        boolean hasAddress = n.locationAddress() != null && !n.locationAddress().isBlank();

        String page = formatSourcePage(n.sourcePageTitle(), n.sourcePageUrl());
        boolean hasAd = n.adCampaign() != null && !n.adCampaign().isBlank();
        // The client's own words from the booking form (2026-10-04 owner request: the note was
        // never shown, so staff called without knowing what the client had already told them).
        boolean hasNote = n.note() != null && !n.note().isBlank();
        String typeEn = n.online() ? "Online (phone call)" : hasAddress ? "In person — " + n.locationAddress() : "In person";
        String typeRu = n.online() ? "Онлайн (по телефону)" : hasAddress ? "Очно — " + n.locationAddress() : "Очно";

        String en = (n.online() ? "🆕 New free consultation booked\n" : "🆕 New in-studio consultation booked\n")
                + "👤 Client: " + client + "\n"
                + "💇 Artist: " + artist + "\n"
                + "📱 Phone: " + phone + "\n"
                + "🕐 Appointment: " + when + "\n"
                + "📍 Format: " + typeEn + "\n"
                + "📄 Page: " + page
                + (hasAd ? "\n📣 Ad: " + n.adCampaign().strip() : "")
                + (hasNote ? "\n💬 Client's note: " + n.note().strip() : "");

        String ru = (n.online() ? "🆕 Забронирована бесплатная консультация\n" : "🆕 Забронирована консультация в студии\n")
                + "👤 Клиент: " + client + "\n"
                + "💇 Мастер: " + artist + "\n"
                + "📱 Телефон: " + phone + "\n"
                + "🕐 Запись: " + when + "\n"
                + "📍 Формат: " + typeRu + "\n"
                + "📄 Страница: " + page
                + (hasAd ? "\n📣 Реклама: " + n.adCampaign().strip() : "")
                + (hasNote ? "\n💬 Комментарий клиента: " + n.note().strip() : "");

        return en + "\n\n—\n\n" + ru;
    }

    /** "Permanent Lips — https://…" when both are known, whichever one is known otherwise, "—" if
     * neither. Package-private for direct unit testing. */
    static String formatSourcePage(String title, String url) {
        boolean hasTitle = title != null && !title.isBlank();
        boolean hasUrl = url != null && !url.isBlank();
        if (hasTitle && hasUrl) return title.strip() + " — " + url.strip();
        if (hasUrl) return url.strip();
        if (hasTitle) return title.strip();
        return "—";
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

    /** Alerts a business's staff Telegram channel when {@code ProviderScheduleClosureAlertScheduler}
     * finds that a provider's own Square calendar lost one or more previously-open slots starting
     * less than a day out, with no real customer booking behind the gap — i.e. the provider (not a
     * customer) closed that time themselves, on short notice. Grouped into one alert per provider
     * per poll (a provider very often blocks their whole remaining day at once, not one slot at a
     * time — see the scheduler's own doc) rather than one message per slot. Business-scoped via
     * {@code businessId}, same pattern as {@link #sendSameDayBookingAlert}/
     * {@link #sendPaymentFailedAlert}. Never throws, same contract as every other send method
     * here. */
    public boolean sendProviderScheduleClosureAlert(Long businessId, String providerName, int slotCount,
                                                      Instant earliestSlotAt, Instant latestSlotAt) {
        TelegramNotificationConfig cfg = configService.get(businessId);
        String token = cfg.getBotToken();
        String chatId = cfg.getChatId();
        if (token == null || token.isBlank() || chatId == null || chatId.isBlank()) {
            log.info("Provider-schedule-closure Telegram alert skipped — bot token or chat id not configured for business {}", businessId);
            return false;
        }

        String text = businessLabel(businessId) + formatProviderScheduleClosureMessage(providerName, slotCount, earliestSlotAt, latestSlotAt);
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
                log.warn("Provider-schedule-closure Telegram alert send failed: HTTP {} {}", res.statusCode(), res.body());
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("Provider-schedule-closure Telegram alert send failed (caller unaffected): {}", e.getMessage());
            return false;
        }
    }

    /** English on top, Russian below a divider — same reasoning and audience as
     * {@link #formatSameDayBookingMessage}. A single slot reads as one time ("2:00 PM"); more than
     * one reads as a range ("2:00 PM – 6:00 PM") — a provider closing several consecutive slots at
     * once (very often their whole remaining day) reads as one clear window rather than a list.
     * Package-private for direct unit testing. */
    static String formatProviderScheduleClosureMessage(String providerName, int slotCount,
                                                          Instant earliestSlotAt, Instant latestSlotAt) {
        String when = slotCount <= 1 || earliestSlotAt.equals(latestSlotAt)
                ? PACIFIC_TIME_FORMATTER.format(earliestSlotAt)
                : PACIFIC_TIME_FORMATTER.format(earliestSlotAt) + " – " + PACIFIC_TIME_FORMATTER.format(latestSlotAt);

        String en = "📅 " + providerName + " closed " + slotCount + (slotCount == 1 ? " slot" : " slots")
                + " on their own calendar with less than a day's notice\n"
                + "🕐 " + when + "\n\n"
                + "Please check whether this was actually arranged in advance.";

        String ru = "📅 " + providerName + " закрыл(а) " + slotCount + (slotCount == 1 ? " слот" : " слотов")
                + " в своём календаре меньше чем за день\n"
                + "🕐 " + when + "\n\n"
                + "Пожалуйста, проверьте, было ли это действительно согласовано заранее.";

        return en + "\n\n—\n\n" + ru;
    }
}
