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
import java.util.List;

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
        return send(config, toPhoneNumber, body, List.of());
    }

    /** Same as {@link #send(TwilioSmsConfig, String, String)}, with one or more MMS attachments.
     * Each entry in {@code mediaUrls} must be a publicly-fetchable HTTPS URL — Twilio's own
     * servers download it, the classic REST create-message call can't accept raw bytes directly
     * (see {@code SmsMediaController}, which is what these URLs point at). An empty list sends a
     * plain SMS, identical to the no-media overload. */
    public String send(TwilioSmsConfig config, String toPhoneNumber, String body, List<String> mediaUrls)
            throws IOException, InterruptedException {
        String credentials = config.getApiKey() + ":" + config.getApiSecret();
        String auth = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        StringBuilder form = new StringBuilder()
                .append("To=").append(encode(toPhoneNumber))
                .append("&From=").append(encode(config.getFromPhoneNumber()))
                .append("&Body=").append(encode(body))
                .append("&StatusCallback=").append(encode(statusCallbackUrl));
        for (String mediaUrl : mediaUrls) {
            form.append("&MediaUrl=").append(encode(mediaUrl));
        }

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.twilio.com/2010-04-01/Accounts/" + config.getAccountSid() + "/Messages.json"))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Basic " + auth)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.net.http.HttpTimeoutException e) {
            throw new TwilioSendException("Twilio API timed out", 0, "timeout", e);
        } catch (IOException e) {
            throw new TwilioSendException("Twilio API unreachable: " + e.getMessage(), 0, "network", e);
        }
        if (res.statusCode() < 200 || res.statusCode() >= 300) {
            throw new TwilioSendException("Twilio API returned " + res.statusCode() + ": " + res.body(),
                    res.statusCode(), errorCodeOf(res.body()), null);
        }
        try {
            return mapper.readValue(res.body(), MessageResponse.class).sid();
        } catch (Exception e) {
            return null; // sent successfully; SID just isn't recorded — not worth failing the send over
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MessageStatus(String status, Integer error_code) {}

    /** Current delivery state of a sent message, used by the reconciler for rows whose status
     * callback never arrived. Throws on any non-2xx response or transport failure. */
    public MessageStatus fetchStatus(TwilioSmsConfig config, String messageSid) throws IOException, InterruptedException {
        String credentials = config.getApiKey() + ":" + config.getApiSecret();
        String auth = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.twilio.com/2010-04-01/Accounts/" + config.getAccountSid() + "/Messages/" + messageSid + ".json"))
                .timeout(Duration.ofSeconds(8))
                .header("Authorization", "Basic " + auth)
                .GET()
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() < 200 || res.statusCode() >= 300) {
            throw new IOException("Twilio status fetch returned " + res.statusCode());
        }
        return mapper.readValue(res.body(), MessageStatus.class);
    }

    static String errorCodeOf(String body) {
        try {
            var code = mapper_static.readTree(body).get("code");
            return code == null || code.isNull() ? null : code.asText();
        } catch (Exception e) {
            return null;
        }
    }

    private static final ObjectMapper mapper_static = new ObjectMapper();

    /** Downloads one inbound MMS attachment from Twilio's {@code MediaUrl{n}} webhook param — same
     * Basic Auth pair as {@link #send}, since Twilio requires it on media fetches too. Throws on
     * any non-2xx response or transport failure, same contract as {@link #send}. */
    public byte[] fetchMedia(TwilioSmsConfig config, String mediaUrl) throws IOException, InterruptedException {
        String credentials = config.getApiKey() + ":" + config.getApiSecret();
        String auth = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(mediaUrl))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Basic " + auth)
                .GET()
                .build();
        HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        // Twilio answers a media URL with a 307 to a short-lived signed CDN link
        // (mms.twiliocdn.com/...?Expires=&Signature=). Found 2026-10-05: every inbound photo since
        // ~2026-08 failed here with "returned 307", so clients' photos never showed in
        // /admin/messages. Follow it once, WITHOUT our credentials: the link is already signed,
        // and a second auth scheme on a signed URL is rejected.
        if (res.statusCode() >= 300 && res.statusCode() < 400) {
            int redirectStatus = res.statusCode();
            String location = res.headers().firstValue("Location")
                    .orElseThrow(() -> new IOException("Twilio media fetch returned " + redirectStatus + " without Location"));
            HttpRequest cdn = HttpRequest.newBuilder()
                    .uri(URI.create(mediaUrl).resolve(location))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            res = http.send(cdn, HttpResponse.BodyHandlers.ofByteArray());
        }
        if (res.statusCode() < 200 || res.statusCode() >= 300) {
            throw new IOException("Twilio media fetch returned " + res.statusCode());
        }
        return res.body();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
