package com.salonreview.seo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salonreview.domain.SeoPageSnapshot;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;

/**
 * Thin read-only client over the PageSpeed Insights API — unlike {@link SearchConsoleClient}/
 * {@link GoogleAnalyticsClient}, this API is authenticated with a plain API key (not OAuth), so
 * there's no {@link GoogleServiceAccountAuth} involved here. Real-world behavior confirmed during
 * manual testing (2026-09-01): this API returns transient {@code 500}/"Something went wrong"
 * errors reasonably often — callers should expect and tolerate occasional failures on a single
 * run, not treat one as a sign of a broken connection (see design.md Risks).
 */
public class PageSpeedInsightsClient {

    private static final String BASE_URL = "https://www.googleapis.com/pagespeedonline/v5";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final RestClient http;
    private final String apiKey;

    public PageSpeedInsightsClient(String apiKey) {
        this(GoogleRestClients.builder(BASE_URL).build(), apiKey);
    }

    PageSpeedInsightsClient(RestClient http, String apiKey) {
        this.http = http;
        this.apiKey = apiKey;
    }

    public record Result(int performanceScore, Integer lcpMs, BigDecimal cls, Integer fcpMs, Integer tbtMs) {
    }

    public Result check(String url, SeoPageSnapshot.Strategy strategy) {
        JsonNode response;
        try {
            response = http.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/runPagespeed")
                            .queryParam("url", url)
                            .queryParam("strategy", strategy.name().toLowerCase())
                            .queryParam("category", "performance")
                            .queryParam("key", apiKey)
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            // Real errors seen live (2026-09-01): 500 "Something went wrong" (design.md Risks) and
            // 400 NO_FCP ("the page did not paint any content", a transient Lighthouse-runner
            // failure, not necessarily a real regression on the site being checked) — both come
            // back as a JSON body with a nested error.message, which is what an owner actually
            // needs to see, not the whole raw response RestClientResponseException.getMessage()
            // would otherwise surface verbatim.
            throw new IllegalStateException(extractErrorMessage(e), e);
        }

        JsonNode categories = response.at("/lighthouseResult/categories");
        int performanceScore = (int) Math.round(categories.path("performance").path("score").asDouble(0) * 100);

        JsonNode audits = response.at("/lighthouseResult/audits");
        Integer lcpMs = numericValueMs(audits, "largest-contentful-paint");
        Integer fcpMs = numericValueMs(audits, "first-contentful-paint");
        Integer tbtMs = numericValueMs(audits, "total-blocking-time");
        BigDecimal cls = numericValue(audits, "cumulative-layout-shift");

        return new Result(performanceScore, lcpMs, cls, fcpMs, tbtMs);
    }

    private static String extractErrorMessage(RestClientResponseException e) {
        try {
            JsonNode body = OBJECT_MAPPER.readTree(e.getResponseBodyAsString());
            JsonNode message = body.at("/error/message");
            if (!message.isMissingNode() && !message.isNull()) {
                return "Lighthouse error (" + e.getStatusCode().value() + "): " + message.asText();
            }
        } catch (Exception parseFailure) {
            // Fall through to the generic message below — this is a best-effort cleanup, not a
            // guarantee every possible PSI error response is valid parseable JSON.
        }
        return "PageSpeed request failed with " + e.getStatusCode();
    }

    private static Integer numericValueMs(JsonNode audits, String auditKey) {
        BigDecimal value = numericValue(audits, auditKey);
        return value == null ? null : value.setScale(0, java.math.RoundingMode.HALF_UP).intValueExact();
    }

    private static BigDecimal numericValue(JsonNode audits, String auditKey) {
        JsonNode node = audits.path(auditKey).path("numericValue");
        return node.isMissingNode() || node.isNull() ? null : BigDecimal.valueOf(node.asDouble());
    }
}
