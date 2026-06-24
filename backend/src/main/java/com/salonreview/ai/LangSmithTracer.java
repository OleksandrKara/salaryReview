package com.salonreview.ai;

import com.salonreview.config.AiTriageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Async wrapper around {@link LangSmithClient} that runs trace ships off the request thread and
 * swallows all failures. The contract: callers get a {@link Trace} handle they can complete with
 * outputs and usage, plus a feedback method for shipping graded runs after the owner takes an
 * action. Nothing this class does can fail in a way that affects the user-facing response.
 */
@Component
@ConditionalOnExpression("${ai.triage.enabled:false} or ${rag.enabled:false}")
public class LangSmithTracer {

    private static final Logger log = LoggerFactory.getLogger(LangSmithTracer.class);

    private final LangSmithClient client;
    private final AiTriageProperties props;
    private final ExecutorService executor;

    public LangSmithTracer(LangSmithClient client, AiTriageProperties props) {
        this.client = client;
        this.props = props;
        // Small fixed pool: trace ships are short HTTPS calls and we never want them to back up.
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "langsmith-tracer");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Open a trace for an LLM call. The caller invokes {@link Trace#complete} once with outputs +
     * usage when the call finishes (success or failure). Both create and update ship async.
     */
    public Trace startTrace(String name, Map<String, String> tags, Map<String, Object> inputs) {
        String runId = UUID.randomUUID().toString();
        Instant startedAt = Instant.now();

        if (!props.isLangsmithConfigured()) {
            return new Trace(runId, startedAt, /* enabled */ false);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("id", runId);
        body.put("name", name);
        body.put("run_type", "llm");
        body.put("start_time", startedAt.toString());
        body.put("inputs", inputs);
        body.put("session_name", props.getLangsmithProject());
        body.put("extra", Map.of("tags", tags));

        CompletableFuture.runAsync(() -> client.createRun(body), executor)
                .exceptionally(e -> {
                    log.warn("LangSmith createRun async dispatch failed: {}", e.toString());
                    return null;
                });

        return new Trace(runId, startedAt, true);
    }

    /** Ship a feedback event linked to a prior trace. Score is in [0.0, 1.0]. */
    public void feedback(String runId, double score, String key, Map<String, Object> metadata) {
        if (!props.isLangsmithConfigured() || runId == null || runId.isBlank()) return;

        Map<String, Object> body = new HashMap<>();
        body.put("run_id", runId);
        body.put("key", key);
        body.put("score", score);
        if (metadata != null && !metadata.isEmpty()) body.put("extra", Map.of("metadata", metadata));

        CompletableFuture.runAsync(() -> client.postFeedback(runId, body), executor)
                .exceptionally(e -> {
                    log.warn("LangSmith postFeedback async dispatch failed: {}", e.toString());
                    return null;
                });
    }

    /**
     * Handle returned from {@link #startTrace}. Call {@link #complete} exactly once when the LLM
     * call finishes — success or failure. The completion ships async; the caller never blocks.
     */
    public final class Trace {
        private final String runId;
        private final Instant startedAt;
        private final boolean enabled;

        private Trace(String runId, Instant startedAt, boolean enabled) {
            this.runId = runId;
            this.startedAt = startedAt;
            this.enabled = enabled;
        }

        public String runId() { return runId; }

        public void complete(Map<String, Object> outputs, Map<String, Object> usage, String error) {
            if (!enabled) return;
            Map<String, Object> body = new HashMap<>();
            body.put("end_time", Instant.now().toString());
            if (outputs != null) body.put("outputs", outputs);
            if (error != null) body.put("error", error);
            if (usage != null) body.put("extra", Map.of("usage", usage));

            CompletableFuture.runAsync(() -> client.updateRun(runId, body), executor)
                    .exceptionally(e -> {
                        log.warn("LangSmith updateRun async dispatch failed: {}", e.toString());
                        return null;
                    });
        }
    }
}
