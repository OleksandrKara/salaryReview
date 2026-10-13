package com.salonreview.marketing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Creates or updates a HubSpot contact by email (CRM v3 batch upsert), authenticated with a
 * private-app token ({@code HUBSPOT_PRIVATE_APP_TOKEN}, scope crm.objects.contacts.write). Used for
 * PMU Academy course requests. Only standard contact properties are written, so nothing has to be
 * created in the HubSpot portal first.
 */
@Component
public class HubSpotContactClient {

    static final String UPSERT_URL = "https://api.hubapi.com/crm/v3/objects/contacts/batch/upsert";

    private final String token;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http;

    public HubSpotContactClient(@Value("${hubspot.private-app-token:}") String token) {
        this(token, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    HubSpotContactClient(String token, HttpClient http) {
        this.token = token;
        this.http = http;
    }

    public boolean isConfigured() {
        return token != null && !token.isBlank();
    }

    public record Result(String contactId, String error) {
    }

    /** Never throws; a failure comes back as {@link Result#error()}. */
    public Result upsertByEmail(String email, Map<String, String> properties) {
        if (!isConfigured()) return new Result(null, "not_configured");
        if (email == null || email.isBlank()) return new Result(null, "no_email");
        Map<String, String> props = new LinkedHashMap<>();
        properties.forEach((k, v) -> {
            if (v != null && !v.isBlank()) props.put(k, v);
        });
        props.put("email", email.trim());
        Map<String, Object> body = Map.of("inputs", List.of(Map.of(
                "idProperty", "email", "id", email.trim(), "properties", props)));
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(UPSERT_URL))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                return new Result(null, "HTTP " + res.statusCode() + ": " + truncate(res.body()));
            }
            JsonNode id = json.readTree(res.body()).path("results").path(0).path("id");
            return new Result(id.isMissingNode() ? null : id.asText(), null);
        } catch (Exception e) {
            return new Result(null, truncate(String.valueOf(e.getMessage())));
        }
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
