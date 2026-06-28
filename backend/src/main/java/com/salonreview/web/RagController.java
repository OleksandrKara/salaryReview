package com.salonreview.web;

import com.salonreview.ai.LangSmithTracer;
import com.salonreview.config.RagProperties;
import com.salonreview.rag.Citation;
import com.salonreview.rag.RagAnswer;
import com.salonreview.rag.RagAnswerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * OWNER+MANAGER question-answering endpoints. Sits under {@code /api/rag/**} (OWNER+MANAGER gate in
 * SecurityConfig; the OWNER-only {@code /api/rag/admin/**} matcher is listed first so it wins).
 * Feature-flag gate: 404 when {@code rag.enabled=false}.
 */
@RestController
@RequestMapping("/api/rag")
@ConditionalOnProperty(prefix = "rag", name = "enabled", havingValue = "true")
public class RagController {

    private static final Logger log = LoggerFactory.getLogger(RagController.class);

    private final RagAnswerService answerService;
    private final ObjectProvider<LangSmithTracer> tracerProvider;
    private final RagProperties props;
    // Streaming runs the (blocking) Anthropic stream off the request thread. Daemon, small pool.
    private final ExecutorService streamExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "rag-stream");
        t.setDaemon(true);
        return t;
    });

    public RagController(RagAnswerService answerService, ObjectProvider<LangSmithTracer> tracerProvider,
                        RagProperties props) {
        this.answerService = answerService;
        this.tracerProvider = tracerProvider;
        this.props = props;
    }

    /**
     * Stream a grounded answer token-by-token as Server-Sent Events: {@code token} (text deltas),
     * then {@code citations} (sources), then {@code done} ({@code {traceRunId, answered}}) — or
     * {@code error}. JSON-encoded data so newlines in tokens don't break SSE framing. Gating is via
     * SecurityConfig ({@code /api/rag/**} = OWNER+MANAGER) + this controller's feature-flag condition.
     */
    @PostMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStream(@RequestBody AskRequest body) {
        SseEmitter emitter = new SseEmitter(120_000L); // 2 min ceiling per answer
        if (body == null || body.question() == null || body.question().isBlank()) {
            send(emitter, "error", Map.of("message", "A question is required."));
            emitter.complete();
            return emitter;
        }
        RagAnswerService.StreamSink sink = new RagAnswerService.StreamSink() {
            @Override public void token(String text) { send(emitter, "token", Map.of("text", text)); }
            @Override public void citations(List<Citation> citations) { send(emitter, "citations", citations); }
            @Override public void done(String traceRunId, boolean answered) {
                Map<String, Object> d = new HashMap<>();
                d.put("traceRunId", traceRunId);
                d.put("answered", answered);
                send(emitter, "done", d);
                emitter.complete();
            }
            @Override public void error(String message) {
                send(emitter, "error", Map.of("message", message));
                emitter.complete();
            }
        };
        streamExecutor.execute(() -> {
            try {
                answerService.answerStream(body.question(), sink);
            } catch (Exception e) {
                log.error("RAG stream failed: {}", e.toString());
                sink.error("The assistant is unavailable right now. Please try again.");
            }
        });
        return emitter;
    }

    /** Send one SSE event; a failed send (client disconnected) is swallowed — the stream just ends. */
    private static void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            // client gone or already completed — nothing to do
        }
    }

    /** Ask a question; returns a grounded, cited answer (or a "don't know" when the corpus lacks it). */
    @PostMapping("/ask")
    public ResponseEntity<RagAnswer> ask(@RequestBody AskRequest body) {
        if (!props.isEnabled()) return ResponseEntity.notFound().build();
        if (body == null || body.question() == null || body.question().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            return ResponseEntity.ok(answerService.answer(body.question()));
        } catch (RagAnswerService.RagAnswerException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
    }

    /** Record thumbs up/down on an answer; ships a graded run to LangSmith linked to its trace. */
    @PostMapping("/ask/feedback")
    public ResponseEntity<Void> feedback(@RequestBody FeedbackRequest body) {
        if (!props.isEnabled()) return ResponseEntity.notFound().build();
        if (body == null || body.runId() == null || body.runId().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        tracerProvider.ifAvailable(tracer -> {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("source", "rag_user_feedback");
            tracer.feedback(body.runId(), body.helpful() ? 1.0 : 0.0, "rag_user_feedback", metadata);
        });
        return ResponseEntity.ok().build();
    }

    public record AskRequest(String question) {}

    public record FeedbackRequest(String runId, boolean helpful) {}
}
