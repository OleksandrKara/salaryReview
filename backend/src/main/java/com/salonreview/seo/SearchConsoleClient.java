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

    public record QueryRow(LocalDate date, String query, String page, long clicks, long impressions,
            BigDecimal ctr, BigDecimal position) {
    }

    /** Search Console's own per-request ceiling; asking for more is capped by the API anyway. */
    public static final int MAX_PAGE_SIZE = 25_000;
    private static final int MAX_PAGES = 40;

    /** Per-query performance for a whole date range, {@code dimensions} always {@code ["date",
     * "query", "page"]} so every row carries all three, matching {@code
     * seo_search_metrics_snapshot}'s shape. One call per {@code rowLimit}-sized page of the result
     * (not one call per day, which tripped the proxy's 60s timeout for GA4), walking {@code
     * startRow} until a page comes back short. Without the walk the API returned only the first
     * {@code rowLimit} rows sorted by clicks, and every later day of a busy window kept just its
     * clicked rows (found 2026-10-22: PMU showed 7 impressions a day instead of ~2500). */
    public List<QueryRow> queryPerformance(String siteUrl, LocalDate startDate, LocalDate endDate, int rowLimit) {
        int pageSize = Math.min(Math.max(rowLimit, 1), MAX_PAGE_SIZE);
        List<QueryRow> rows = new ArrayList<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            int fetched = fetchPage(siteUrl, startDate, endDate, pageSize, page * pageSize, rows);
            if (fetched < pageSize) return rows;
        }
        throw new IllegalStateException("Search Console returned more than " + MAX_PAGES * pageSize
                + " rows for " + startDate + ".." + endDate + "; refusing to save a truncated window");
    }

    private int fetchPage(String siteUrl, LocalDate startDate, LocalDate endDate, int pageSize, int startRow,
            List<QueryRow> out) {
        // A real Map, not a hand-formatted JSON string — a raw String body handed to a
        // Jackson-message-converter-equipped RestClient gets serialized AS a JSON string
        // (quoted/escaped), not written through as-is, producing exactly the "root element must
        // be a message" 400 this hit during manual verification against the real API. Letting the
        // registered Jackson converter serialize a real Map avoids that double-encoding entirely.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("startDate", startDate.toString());
        body.put("endDate", endDate.toString());
        body.put("dimensions", List.of("date", "query", "page"));
        body.put("rowLimit", pageSize);
        body.put("startRow", startRow);

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

        int count = 0;
        JsonNode entries = response == null ? null : response.get("rows");
        if (entries != null) {
            for (JsonNode row : entries) {
                JsonNode keys = row.get("keys");
                LocalDate date = keys != null && keys.size() > 0 ? LocalDate.parse(keys.get(0).asText()) : null;
                String query = keys != null && keys.size() > 1 ? keys.get(1).asText() : null;
                String page = keys != null && keys.size() > 2 ? keys.get(2).asText() : null;
                count++;
                out.add(new QueryRow(date, query, page,
                        row.path("clicks").asLong(0),
                        row.path("impressions").asLong(0),
                        BigDecimal.valueOf(row.path("ctr").asDouble(0)),
                        BigDecimal.valueOf(row.path("position").asDouble(0))));
            }
        }
        return count;
    }
}
