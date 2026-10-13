package com.salonreview.marketing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sends a lead to HubSpot as a submission of a HubSpot form (Forms API v3 "integration/submit").
 * That endpoint needs no token: HubSpot creates or updates the contact by email and records the
 * conversion on the form, so the academy shows up as the lead source in HubSpot. Portal
 * 40072325 = pmu-annakara.com; the form "PMU Academy: course request" was created for this on
 * 2026-10-13. An empty portal or form id switches the leg off.
 */
@Component
public class HubSpotFormClient {

    private final String portalId;
    private final String formGuid;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http;

    @Autowired
    public HubSpotFormClient(@Value("${hubspot.portal-id:}") String portalId,
                             @Value("${hubspot.academy-form-guid:}") String formGuid) {
        this(portalId, formGuid, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    HubSpotFormClient(String portalId, String formGuid, HttpClient http) {
        this.portalId = portalId;
        this.formGuid = formGuid;
        this.http = http;
    }

    public boolean isConfigured() {
        return portalId != null && !portalId.isBlank() && formGuid != null && !formGuid.isBlank();
    }

    public record Result(boolean submitted, String error) {
    }

    /** Never throws; a failure comes back as {@link Result#error()}. */
    public Result submit(String email, Map<String, String> properties, String pageUri, String pageName) {
        if (!isConfigured()) return new Result(false, "not_configured");
        if (email == null || email.isBlank()) return new Result(false, "no_email");
        Map<String, String> props = new LinkedHashMap<>();
        props.put("email", email.trim());
        properties.forEach((k, v) -> {
            if (v != null && !v.isBlank()) props.put(k, v);
        });
        List<Map<String, String>> fields = new ArrayList<>();
        props.forEach((k, v) -> fields.add(Map.of("objectTypeId", "0-1", "name", k, "value", v)));
        Map<String, Object> context = new LinkedHashMap<>();
        if (pageUri != null && !pageUri.isBlank()) context.put("pageUri", pageUri);
        if (pageName != null && !pageName.isBlank()) context.put("pageName", pageName);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fields", fields);
        if (!context.isEmpty()) body.put("context", context);
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.hsforms.com/submissions/v3/integration/submit/" + portalId + "/" + formGuid))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                return new Result(false, "HTTP " + res.statusCode() + ": " + truncate(res.body()));
            }
            return new Result(true, null);
        } catch (Exception e) {
            return new Result(false, truncate(String.valueOf(e.getMessage())));
        }
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
