package com.salonreview.seo;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
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

    /** Site-wide unique users for one day, plus the organic-search-channel slice of that day's
     * sessions. Two separate report requests, not one dimension-broken-down report: GA4's {@code
     * totalUsers}/{@code newUsers} are distinct-count metrics that can't be summed across a
     * dimension breakdown without double-counting a user who arrived via two channels the same
     * day, so the users report has no channel dimension at all (one row per date, site-wide); the
     * sessions report is separately scoped to just the "Organic Search" channel via a {@code
     * dimensionFilter}, since a plain count metric like sessions has no such double-counting risk.
     * Both reports DO carry a "date" dimension, though, since breaking down by date (unlike by
     * channel) can't cause that kind of double-counting — each row already is one day's total, so
     * requesting the whole window as one call per report (2 calls total) is exactly equivalent to
     * the one-call-per-day loop this replaced, just without the ~2×window-days sequential Google
     * API round trips that were slow enough to trip the reverse proxy's read timeout (a 28-day
     * sync used to fire 56 sequential requests here alone, on top of Search Console's and
     * PageSpeed's own calls in the same request). */
    public record DailyTotals(long totalUsers, long newUsers, long organicSessions) {
    }

    public Map<LocalDate, DailyTotals> dailyTotals(String propertyId, LocalDate startDate, LocalDate endDate) {
        Map<LocalDate, long[]> userTotals = runTotalsRangeReport(propertyId, startDate, endDate);
        Map<LocalDate, Long> organicSessions = runOrganicSessionsRangeReport(propertyId, startDate, endDate);

        Map<LocalDate, DailyTotals> result = new LinkedHashMap<>();
        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            long[] totals = userTotals.getOrDefault(date, new long[] {0, 0});
            result.put(date, new DailyTotals(totals[0], totals[1], organicSessions.getOrDefault(date, 0L)));
        }
        return result;
    }

    private Map<LocalDate, long[]> runTotalsRangeReport(String propertyId, LocalDate startDate, LocalDate endDate) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dateRanges", List.of(Map.of("startDate", startDate.toString(), "endDate", endDate.toString())));
        body.put("dimensions", List.of(Map.of("name", "date")));
        body.put("metrics", List.of(Map.of("name", "totalUsers"), Map.of("name", "newUsers")));

        Map<LocalDate, long[]> byDate = new LinkedHashMap<>();
        JsonNode response = runReport(propertyId, body);
        JsonNode entries = response == null ? null : response.get("rows");
        if (entries != null) {
            for (JsonNode row : entries) {
                byDate.put(parseGa4Date(row.at("/dimensionValues/0/value").asText()), new long[] {
                        row.at("/metricValues/0/value").asLong(0),
                        row.at("/metricValues/1/value").asLong(0),
                });
            }
        }
        return byDate;
    }

    private Map<LocalDate, Long> runOrganicSessionsRangeReport(String propertyId, LocalDate startDate, LocalDate endDate) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dateRanges", List.of(Map.of("startDate", startDate.toString(), "endDate", endDate.toString())));
        body.put("dimensions", List.of(Map.of("name", "date")));
        body.put("metrics", List.of(Map.of("name", "sessions")));
        body.put("dimensionFilter", Map.of("filter", Map.of(
                "fieldName", "sessionDefaultChannelGroup",
                "stringFilter", Map.of("value", "Organic Search"))));

        Map<LocalDate, Long> byDate = new LinkedHashMap<>();
        JsonNode response = runReport(propertyId, body);
        JsonNode entries = response == null ? null : response.get("rows");
        if (entries != null) {
            for (JsonNode row : entries) {
                byDate.put(parseGa4Date(row.at("/dimensionValues/0/value").asText()),
                        row.at("/metricValues/0/value").asLong(0));
            }
        }
        return byDate;
    }

    // GA4's default "date" dimension format, e.g. "20260901" — not the "-"-separated ISO form used
    // in the request's own dateRanges.
    private static LocalDate parseGa4Date(String yyyyMMdd) {
        return LocalDate.parse(yyyyMMdd, DateTimeFormatter.BASIC_ISO_DATE);
    }

    private JsonNode runReport(String propertyId, Map<String, Object> body) {
        return http.post()
                .uri("/properties/{propertyId}:runReport", propertyId)
                .header("Authorization", "Bearer " + auth.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
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
