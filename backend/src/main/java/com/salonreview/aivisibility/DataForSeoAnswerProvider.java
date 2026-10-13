package com.salonreview.aivisibility;

import com.fasterxml.jackson.databind.JsonNode;
import com.salonreview.config.AiVisibilityProperties;
import com.salonreview.domain.AiVisibilityCheck.Engine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/** DataForSEO live endpoints per engine (design.md D1). Retries HTTP 403/429/5xx and task status
 * 40101/50000 (both seen transiently in October 2026) up to twice with 5s/10s backoff. */
@Component
public class DataForSeoAnswerProvider implements AiAnswerProvider {

    private static final Logger log = LoggerFactory.getLogger(DataForSeoAnswerProvider.class);

    private final AiVisibilityProperties props;
    private final DataForSeoHttp http;

    public DataForSeoAnswerProvider(AiVisibilityProperties props, DataForSeoHttp http) {
        this.props = props;
        this.http = http;
    }

    @Override
    public RawAnswer ask(Engine engine, String question, String locationName, String coordinate) {
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
                putLocation(payload, locationName, coordinate);
                payload.put("language_code", "en");
                payload.put("device", "mobile");
            }
            case GOOGLE_AI_OVERVIEW -> {
                // The AI Overview block in the ordinary Google results (mobile, San Diego). Google
                // shows it only for some questions; no block = NO_ANSWER, not "not named".
                endpoint = "serp/google/organic/live/advanced";
                model = "google-ai-overview";
                payload.put("keyword", question);
                putLocation(payload, locationName, coordinate);
                payload.put("language_code", "en");
                payload.put("device", "mobile");
                payload.put("os", "android");
                payload.put("depth", 10);
                payload.put("load_async_ai_overview", true);
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
        JsonNode task = http.post(endpoint, payload);
        BigDecimal cost = task.path("cost").isNumber() ? task.path("cost").decimalValue() : BigDecimal.ZERO;
        JsonNode result = task.path("result").isArray() && !task.path("result").isEmpty() ? task.path("result").get(0) : null;
        String reportedModel = result != null && result.hasNonNull("model") ? result.get("model").asText()
                : result != null && result.hasNonNull("model_name") ? result.get("model_name").asText() : model;
        return new RawAnswer(result, cost, reportedModel);
    }

    private static void putLocation(Map<String, Object> payload, String locationName, String coordinate) {
        if (coordinate != null && !coordinate.isBlank()) payload.put("location_coordinate", coordinate);
        else payload.put("location_name", locationName);
    }

    private static void llmPayload(Map<String, Object> payload, String question, String model) {
        payload.put("user_prompt", question);
        payload.put("model_name", model);
        payload.put("web_search", true);
    }

}
