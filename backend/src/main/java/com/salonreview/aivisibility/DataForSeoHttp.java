package com.salonreview.aivisibility;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salonreview.config.AiVisibilityProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** One DataForSEO "live" POST, shared by the AI answer checks and the page-comparison fallback
 * (design.md D1, D10). Retries HTTP 403/429/5xx and task status 40101/50000 (both seen transiently in
 * October 2026) twice with 5s/10s backoff; returns the task object (with {@code cost} and
 * {@code result}). */
@Component
public class DataForSeoHttp {

    private final AiVisibilityProperties props;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    public DataForSeoHttp(AiVisibilityProperties props) {
        this.props = props;
    }

    public JsonNode post(String endpoint, Map<String, Object> payload) {
        return post(endpoint, payload, 2);
    }

    private JsonNode post(String endpoint, Map<String, Object> payload, int retriesLeft) {
        String auth = Base64.getEncoder().encodeToString(
                (props.getDataforseoLogin() + ":" + props.getDataforseoPassword()).getBytes(StandardCharsets.UTF_8));
        for (int attempt = 0; ; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(props.getDataforseoBaseUrl() + endpoint))
                        .timeout(Duration.ofSeconds(150))
                        .header("Authorization", "Basic " + auth)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(List.of(payload))))
                        .build();
                HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int code = res.statusCode();
                if (code == 403 || code == 429 || code >= 500) {
                    if (attempt < retriesLeft) { sleep(attempt); continue; }
                    throw new IllegalStateException(endpoint + ": HTTP " + code);
                }
                if (code < 200 || code >= 300) {
                    throw new IllegalStateException(endpoint + ": HTTP " + code + " " + abbreviate(res.body()));
                }
                JsonNode task = json.readTree(res.body()).path("tasks").path(0);
                int status = task.path("status_code").asInt();
                if ((status == 40101 || status == 50000) && attempt < retriesLeft) { sleep(attempt); continue; }
                if (status != 20000) {
                    throw new IllegalStateException(endpoint + ": " + status + " " + task.path("status_message").asText());
                }
                return task;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", e);
            } catch (java.io.IOException e) {
                if (attempt < retriesLeft) { sleep(attempt); continue; }
                throw new IllegalStateException(endpoint + ": " + e.getMessage(), e);
            }
        }
    }

    private static void sleep(int attempt) {
        try {
            Thread.sleep(5000L * (attempt + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String abbreviate(String s) {
        return s == null ? "" : s.length() > 300 ? s.substring(0, 300) : s;
    }
}
