package com.salonreview.sms;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salonreview.domain.TwilioSmsConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Thin hand-rolled client for Twilio's Messages API — same style as {@code VoyageClient}/
 * {@code TelegramNotificationService}, no SDK dependency.
 *
 * <p>Auth: HTTP Basic with the API Key SID as username and API Key Secret as password — the
 * Account SID is only the URL path segment, not part of the Basic Auth pair (confirmed against
 * Twilio's actual API shape; see design.md D4).
 */
@Component
public class TwilioSmsClient {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    /** Same URL {@link TwilioStatusCallbackController} validates its signature against — both
     * derive it from {@code app.public-base-url}, so there's no separate setting to keep in sync. */
    private final String statusCallbackUrl;

    public TwilioSmsClient(@Value("${app.public-base-url}") String publicBaseUrl) {
        this.statusCallbackUrl = publicBaseUrl + "/api/public/sms/status";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MessageResponse(String sid) {}

    /** Throws on any non-2xx response or transport failure — callers must catch and translate.
     * Returns Twilio's message SID for the sent message, recorded on the {@code sms_message} row
     * this send corresponds to (see {@code TwilioSmsService}) — the same SID
     * {@link TwilioStatusCallbackController} later matches its delivery-status callback against. */
    public String send(TwilioSmsConfig config, String toPhoneNumber, String body) throws IOException, InterruptedException {
        String credentials = config.getApiKey() + ":" + config.getApiSecret();
        String auth = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        String form = "To=" + encode(toPhoneNumber)
                + "&From=" + encode(config.getFromPhoneNumber())
                + "&Body=" + encode(body)
                + "&StatusCallback=" + encode(statusCallbackUrl);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.twilio.com/2010-04-01/Accounts/" + config.getAccountSid() + "/Messages.json"))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Basic " + auth)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() < 200 || res.statusCode() >= 300) {
            throw new IOException("Twilio API returned " + res.statusCode() + ": " + res.body());
        }
        try {
            return mapper.readValue(res.body(), MessageResponse.class).sid();
        } catch (Exception e) {
            return null; // sent successfully; SID just isn't recorded — not worth failing the send over
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
