package com.salonreview.seo;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin read-only client over the Search Console API (webmasters/v3) — same "map only what we use,
 * ignore the rest" philosophy as {@link com.salonreview.square.SquareClient}. Authenticated via
 * {@link GoogleServiceAccountAuth} (one instance per business's connection, not a shared/global
 * client — seo-monitoring-dashboard design.md D4, same reasoning as
 * {@code SquareClientProvider}: one client per business keeps credentials and any future
 * rate-limit state correctly tenant-scoped by construction).
 */
public class SearchConsoleClient {

    private static final String BASE_URL = "https://www.googleapis.com/webmasters/v3";
    private static final String SCOPE = "https://www.googleapis.com/auth/webmasters.readonly";

    private final RestClient http;
    private final GoogleServiceAccountAuth auth;

    public SearchConsoleClient(String serviceAccountJson) {
        this(GoogleRestClients.builder(BASE_URL).build(), new GoogleServiceAccountAuth(serviceAccountJson, SCOPE));
    }

    /** Test-only constructor — points this client at an arbitrary {@link RestClient} instead of
     * building one from real credentials, same convention as {@code SquareClient}'s package-private
     * test constructor. */
    SearchConsoleClient(RestClient http, GoogleServiceAccountAuth auth) {
        this.http = http;
        this.auth = auth;
    }

    public record Site(String siteUrl, String permissionLevel) {
    }

    /** Every Search Console property this business's service account can see — used to discover
     * the actual registered site URL (which may be a Domain property, {@code sc-domain:example.com},
     * or a URL-prefix property, {@code https://example.com/} — the exact string differs per
     * business and must never be assumed/hardcoded). */
    public List<Site> sites() {
        JsonNode response = http.get()
                .uri("/sites")
                .header("Authorization", "Bearer " + auth.accessToken())
                .retrieve()
                .body(JsonNode.class);

        List<Site> sites = new ArrayList<>();
        JsonNode entries = response == null ? null : response.get("siteEntry");
        if (entries != null) {
            for (JsonNode entry : entries) {
                sites.add(new Site(entry.path("siteUrl").asText(), entry.path("permissionLevel").asText()));
            }
        }
        return sites;
    }

    public record QueryRow(String query, String page, long clicks, long impressions, BigDecimal ctr, BigDecimal position) {
    }

    /** Per-query performance for one day (a scheduled job calls this once per day, per business —
     * design.md D4). {@code dimensions} is always {@code ["query", "page"]} so every row carries
     * both, matching {@code seo_search_metrics_snapshot}'s shape. */
    public List<QueryRow> queryPerformance(String siteUrl, LocalDate date, int rowLimit) {
        // A real Map, not a hand-formatted JSON string — a raw String body handed to a
        // Jackson-message-converter-equipped RestClient gets serialized AS a JSON string
        // (quoted/escaped), not written through as-is, producing exactly the "root element must
        // be a message" 400 this hit during manual verification against the real API. Letting the
        // registered Jackson converter serialize a real Map avoids that double-encoding entirely.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("startDate", date.toString());
        body.put("endDate", date.toString());
        body.put("dimensions", List.of("query", "page"));
        body.put("rowLimit", rowLimit);

        // Pass the raw siteUrl (e.g. "sc-domain:akluxnails.com") as the URI template variable and
        // let RestClient's own UriComponentsBuilder encode it exactly once — pre-encoding it here
        // ourselves would double-encode the "%" that URLEncoder introduces for the ":" in a domain
        // property's siteUrl.
        JsonNode response = http.post()
                .uri("/sites/{siteUrl}/searchAnalytics/query", siteUrl)
                .header("Authorization", "Bearer " + auth.accessToken())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        List<QueryRow> rows = new ArrayList<>();
        JsonNode entries = response == null ? null : response.get("rows");
        if (entries != null) {
            for (JsonNode row : entries) {
                JsonNode keys = row.get("keys");
                String query = keys != null && keys.size() > 0 ? keys.get(0).asText() : null;
                String page = keys != null && keys.size() > 1 ? keys.get(1).asText() : null;
                rows.add(new QueryRow(query, page,
                        row.path("clicks").asLong(0),
                        row.path("impressions").asLong(0),
                        BigDecimal.valueOf(row.path("ctr").asDouble(0)),
                        BigDecimal.valueOf(row.path("position").asDouble(0))));
            }
        }
        return rows;
    }
}
