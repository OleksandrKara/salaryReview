package com.salonreview.web;

import com.salonreview.ai.LangSmithTracer;
import com.salonreview.config.RagProperties;
import com.salonreview.rag.RagAnswer;
import com.salonreview.rag.RagAnswerService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * OWNER+MANAGER question-answering endpoints. Sits under {@code /api/rag/**} (OWNER+MANAGER gate in
 * SecurityConfig; the OWNER-only {@code /api/rag/admin/**} matcher is listed first so it wins).
 * Feature-flag gate: 404 when {@code rag.enabled=false}.
 */
@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final RagAnswerService answerService;
    private final ObjectProvider<LangSmithTracer> tracerProvider;
    private final RagProperties props;

    public RagController(RagAnswerService answerService, ObjectProvider<LangSmithTracer> tracerProvider,
                        RagProperties props) {
        this.answerService = answerService;
        this.tracerProvider = tracerProvider;
        this.props = props;
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
