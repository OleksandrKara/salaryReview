package com.salonreview.telegram;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salonreview.domain.Business;
import com.salonreview.repo.BusinessRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/** Explicit delivery outcomes for the schedule outbox. Unknown results are never blindly retried. */
@Service
public class ProviderScheduleTelegramSender {
    public record Result(String status, Long messageId) {}
    private final TelegramConfigService config;
    private final BusinessRepository businesses;
    private final HttpClient http;
    private final String baseUrl;
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    public ProviderScheduleTelegramSender(TelegramConfigService config, BusinessRepository businesses) {
        this(config, businesses, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), "https://api.telegram.org");
    }

    ProviderScheduleTelegramSender(TelegramConfigService config, BusinessRepository businesses, HttpClient http, String baseUrl) {
        this.config = config;
        this.businesses = businesses;
        this.http = http;
        this.baseUrl = baseUrl;
    }

    public Result send(Long businessId, String providerName, Instant first, Instant last, int noticeHours, String timezone) {
        var settings = config.get(businessId);
        if (settings.getBotToken() == null || settings.getBotToken().isBlank()
                || settings.getChatId() == null || settings.getChatId().isBlank()) return new Result("FAILED", null);
        try {
            String label = businesses.findById(businessId).map(Business::getName).orElse("Business");
            String message = "🏢 " + label + "\n" + format(providerName, first, last, noticeHours, timezone);
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/bot" + settings.getBotToken() + "/sendMessage"))
                    .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(Map.of("chat_id", settings.getChatId(), "text", message)))).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400 && response.statusCode() < 500) return new Result("FAILED", null);
            if (response.statusCode() < 200 || response.statusCode() >= 300) return new Result("UNKNOWN", null);
            var body = json.readTree(response.body());
            if (!body.path("ok").isBoolean()) return new Result("UNKNOWN", null);
            if (!body.path("ok").asBoolean()) return new Result("FAILED", null);
            if (!body.path("result").path("message_id").isIntegralNumber()) return new Result("UNKNOWN", null);
            return new Result("SENT", body.path("result").path("message_id").longValue());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new Result("UNKNOWN", null);
        } catch (Exception exception) {
            return new Result("UNKNOWN", null);
        }
    }

    static String format(String name, Instant first, Instant last, int noticeHours, String timezone) {
        var formatter = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy 'at' h:mm a zzz", Locale.US).withZone(ZoneId.of(timezone));
        String window = formatter.format(first) + " – " + formatter.format(last);
        String en = "📅 Online booking availability decreased for " + name + "\n"
                + "🕐 Previously available start times: " + window + "\n\n"
                + "Change detected with less than " + noticeHours + " hours' notice and confirmed by a second check.\n"
                + "Customer bookings do not explain the loss.\n"
                + "Please check whether this change was arranged in advance.";
        String ru = "📅 У " + name + " уменьшилась доступность онлайн-записи\n"
                + "🕐 Ранее доступные начала записи: " + window + "\n\n"
                + "Изменение обнаружено менее чем за " + noticeHours + " ч и подтверждено повторной проверкой.\n"
                + "Клиентские записи не объясняют потерю.\n"
                + "Проверьте, было ли изменение согласовано заранее.";
        return en + "\n\n—\n\n" + ru;
    }
}
