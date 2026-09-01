package com.salonreview.seo;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin read-only client over the GA4 Data API (analyticsdata.googleapis.com), authenticated the
 * same way as {@link SearchConsoleClient} — one {@link GoogleServiceAccountAuth} per business
 * connection, not a shared client.
 */
public class GoogleAnalyticsClient {

    private static final String BASE_URL = "https://analyticsdata.googleapis.com/v1beta";
    private static final String SCOPE = "https://www.googleapis.com/auth/analytics.readonly";

    private final RestClient http;
    private final GoogleServiceAccountAuth auth;

    public GoogleAnalyticsClient(String serviceAccountJson) {
        this(GoogleRestClients.builder(BASE_URL).build(), new GoogleServiceAccountAuth(serviceAccountJson, SCOPE));
    }

    GoogleAnalyticsClient(RestClient http, GoogleServiceAccountAuth auth) {
        this.http = http;
        this.auth = auth;
    }

    public record PageRow(String pagePath, long sessions, long screenPageViews) {
    }

    /** Per-page sessions/views for one day — a scheduled job calls this once per day, per
     * business, mirroring {@link SearchConsoleClient#queryPerformance}'s cadence. */
    public List<PageRow> pageViewsByPath(String propertyId, LocalDate date, int limit) {
        // A real nested Map, not a hand-formatted JSON string — see SearchConsoleClient
        // .queryPerformance's comment for why a raw String body gets double-JSON-encoded by the
        // registered Jackson converter instead of written through as-is.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dateRanges", List.of(Map.of("startDate", date.toString(), "endDate", date.toString())));
        body.put("dimensions", List.of(Map.of("name", "pagePath")));
        body.put("metrics", List.of(Map.of("name", "sessions"), Map.of("name", "screenPageViews")));
        body.put("limit", limit);

        JsonNode response = http.post()
                .uri("/properties/{propertyId}:runReport", propertyId)
                .header("Authorization", "Bearer " + auth.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        List<PageRow> rows = new ArrayList<>();
        JsonNode entries = response == null ? null : response.get("rows");
        if (entries != null) {
            for (JsonNode row : entries) {
                String pagePath = row.at("/dimensionValues/0/value").asText();
                long sessions = row.at("/metricValues/0/value").asLong(0);
                long views = row.at("/metricValues/1/value").asLong(0);
                rows.add(new PageRow(pagePath, sessions, views));
            }
        }
        return rows;
    }
}
