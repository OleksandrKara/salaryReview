package com.salonreview.seo;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Read-only client over the Google Ads REST API ({@code googleAds:search}), authenticated with the
 * same service-account flow as the other Google clients here. Google retires API versions on a
 * schedule, so it tries the newest known version first and falls back when one answers 404 or
 * UNSUPPORTED_VERSION. {@code pageSize} is not accepted by the API, so paging follows
 * {@code nextPageToken} only.
 */
public class GoogleAdsClient {

    private static final String BASE_URL = "https://googleads.googleapis.com";
    private static final String SCOPE = "https://www.googleapis.com/auth/adwords";
    private static final List<String> VERSIONS = List.of("v24", "v23");

    private final RestClient http;
    private final GoogleServiceAccountAuth auth;
    private final String developerToken;
    private volatile String workingVersion;

    public GoogleAdsClient(String serviceAccountJson, String developerToken) {
        this(GoogleRestClients.builder(BASE_URL).build(), new GoogleServiceAccountAuth(serviceAccountJson, SCOPE), developerToken);
    }

    GoogleAdsClient(RestClient http, GoogleServiceAccountAuth auth, String developerToken) {
        this.http = http;
        this.auth = auth;
        this.developerToken = developerToken;
    }

    /** Runs a GAQL query and returns every result row (all pages). */
    public List<JsonNode> search(String customerId, String gaql) {
        List<JsonNode> rows = new ArrayList<>();
        String pageToken = null;
        do {
            JsonNode page = post(customerId, gaql, pageToken);
            JsonNode results = page.path("results");
            if (results.isArray()) results.forEach(rows::add);
            pageToken = page.path("nextPageToken").asText(null);
            if (pageToken != null && pageToken.isBlank()) pageToken = null;
        } while (pageToken != null);
        return rows;
    }

    private JsonNode post(String customerId, String gaql, String pageToken) {
        Map<String, Object> body = pageToken == null ? Map.of("query", gaql) : Map.of("query", gaql, "pageToken", pageToken);
        List<String> order = new ArrayList<>(VERSIONS);
        if (workingVersion != null) {
            order.remove(workingVersion);
            order.add(0, workingVersion);
        }
        RuntimeException last = null;
        for (String version : order) {
            try {
                RestClient.RequestBodySpec req = http.post()
                        .uri("/{v}/customers/{c}/googleAds:search", version, customerId)
                        .header("Authorization", "Bearer " + auth.accessToken());
                if (developerToken != null && !developerToken.isBlank()) req = req.header("developer-token", developerToken);
                JsonNode out = req.body(body).retrieve().body(JsonNode.class);
                workingVersion = version;
                return out == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance() : out;
            } catch (HttpStatusCodeException e) {
                String text = e.getResponseBodyAsString();
                if (e.getStatusCode().value() == 404 || (text != null && text.contains("UNSUPPORTED_VERSION"))) {
                    last = e;
                    continue;
                }
                throw new IllegalStateException("Google Ads API " + e.getStatusCode().value() + ": " + abbreviate(text), e);
            }
        }
        throw new IllegalStateException("No supported Google Ads API version answered", last);
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        return s.length() > 400 ? s.substring(0, 400) : s;
    }
}
