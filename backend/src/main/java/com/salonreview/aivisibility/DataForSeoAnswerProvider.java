package com.salonreview.aivisibility;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salonreview.config.AiVisibilityProperties;
import com.salonreview.domain.AiVisibilityCheck.Engine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** DataForSEO live endpoints per engine (design.md D1). Retries HTTP 403/429/5xx and task status
 * 40101/50000 (both seen transiently in October 2026) up to twice with 5s/10s backoff. */
@Component
public class DataForSeoAnswerProvider implements AiAnswerProvider {

    private static final Logger log = LoggerFactory.getLogger(DataForSeoAnswerProvider.class);

    private final AiVisibilityProperties props;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    public DataForSeoAnswerProvider(AiVisibilityProperties props) {
        this.props = props;
    }

    @Override
    public RawAnswer ask(Engine engine, String question, String locationName) {
        Map<String, Object> payload = new LinkedHashMap<>();
        String endpoint;
        String model;
        switch (engine) {
            case CHATGPT -> {
                endpoint = "ai_optimization/chat_gpt/llm_scraper/live/advanced";
                model = "chatgpt.com";
                payload.put("keyword", question);
                payload.put("location_code", 2840);
                payload.put("language_code", "en");
            }
            case GOOGLE_AI_MODE -> {
                endpoint = "serp/google/ai_mode/live/advanced";
                model = "google-ai-mode";
                payload.put("keyword", question);
                payload.put("location_name", locationName);
                payload.put("language_code", "en");
                payload.put("device", "mobile");
            }
            case GEMINI -> {
                endpoint = "ai_optimization/gemini/llm_responses/live";
                model = props.getGeminiModel();
                llmPayload(payload, question, model);
            }
            case PERPLEXITY -> {
                endpoint = "ai_optimization/perplexity/llm_responses/live";
                model = props.getPerplexityModel();
                llmPayload(payload, question, model);
            }
            case CLAUDE -> {
                endpoint = "ai_optimization/claude/llm_responses/live";
                model = props.getClaudeModel();
                llmPayload(payload, question, model);
            }
            default -> throw new IllegalArgumentException("unknown engine " + engine);
        }
        JsonNode task = post(endpoint, payload, 2);
        BigDecimal cost = task.path("cost").isNumber() ? task.path("cost").decimalValue() : BigDecimal.ZERO;
        JsonNode result = task.path("result").isArray() && !task.path("result").isEmpty() ? task.path("result").get(0) : null;
        String reportedModel = result != null && result.hasNonNull("model") ? result.get("model").asText()
                : result != null && result.hasNonNull("model_name") ? result.get("model_name").asText() : model;
        return new RawAnswer(result, cost, reportedModel);
    }

    private static void llmPayload(Map<String, Object> payload, String question, String model) {
        payload.put("user_prompt", question);
        payload.put("model_name", model);
        payload.put("web_search", true);
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
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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
