package com.salonreview.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salonreview.config.AiTriageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Thin HTTPS wrapper around the LangSmith Runs API and Feedback API. We don't depend on a
 * LangSmith SDK because their HTTP API is small and stable; rolling our own keeps the dependency
 * surface tight.
 *
 * <p>All methods are best-effort: a LangSmith outage must never break a user-facing API call.
 * Errors are logged at WARN and swallowed. The {@link LangSmithTracer} layer handles async
 * dispatch so callers don't block on the network.
 */
@Component
@ConditionalOnExpression("${ai.triage.enabled:false} or ${rag.enabled:false}")
public class LangSmithClient {

    private static final Logger log = LoggerFactory.getLogger(LangSmithClient.class);
    private static final String BASE_URL = "https://api.smith.langchain.com";

    private final AiTriageProperties props;
    /**
     * Local ObjectMapper instance — not injected. Spring Boot 4's `spring-boot-starter-webmvc`
     * (the starter this project uses) doesn't register a global `ObjectMapper` bean by default,
     * so we instantiate one here. The shapes we serialize (LangSmith run payloads) don't need
     * any special config that would warrant sharing with the rest of the app.
     */
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http;

    public LangSmithClient(AiTriageProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /** Create a new run in LangSmith. Returns true on 2xx, false on any failure (logged). */
    public boolean createRun(Map<String, Object> body) {
        if (!props.isLangsmithConfigured()) return false;
        return post("/runs", body);
    }

    /** Update a run with outputs / end_time / error. Returns true on 2xx, false on any failure. */
    public boolean updateRun(String runId, Map<String, Object> body) {
        if (!props.isLangsmithConfigured()) return false;
        return patch("/runs/" + runId, body);
    }

    /** Post a feedback event linked to a run. Returns true on 2xx. */
    public boolean postFeedback(String runId, Map<String, Object> body) {
        if (!props.isLangsmithConfigured()) return false;
        return post("/runs/" + runId + "/feedback", body);
    }

    private boolean post(String path, Map<String, Object> body) {
        return send("POST", path, body);
    }

    private boolean patch(String path, Map<String, Object> body) {
        return send("PATCH", path, body);
    }

    private boolean send(String method, String path, Map<String, Object> body) {
        try {
            byte[] payload = json.writeValueAsBytes(body);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + path))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", props.getLangsmithApiKey())
                    .method(method, HttpRequest.BodyPublishers.ofByteArray(payload))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() >= 200 && res.statusCode() < 300) return true;
            log.warn("LangSmith {} {} returned {}: {}", method, path, res.statusCode(),
                    res.body() == null ? "" : res.body().substring(0, Math.min(200, res.body().length())));
            return false;
        } catch (Exception e) {
            log.warn("LangSmith {} {} failed: {}", method, path, e.toString());
            return false;
        }
    }
}
