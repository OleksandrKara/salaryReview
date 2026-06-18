package com.salonreview.web;

import com.salonreview.ai.SuspiciousBookingTriageService;
import com.salonreview.ai.TriageResult;
import com.salonreview.config.AiTriageProperties;
import com.salonreview.domain.TriageClassification;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Owner/manager-only AI triage endpoints. Sits under {@code /api/suspicious/**} so it inherits
 * the existing OWNER+MANAGER role gate in {@code SecurityConfig} — no security wiring change
 * needed.
 *
 * <p>Feature-flag gate: when {@code ai.triage.enabled=false} (the default), both endpoints return
 * 404 so the frontend (which hides the Explain button) can't accidentally call them, and the
 * controller is a no-op for unenabled tenants.
 *
 * <p>Streaming was considered (design D8) but dropped — structured outputs constrain the model
 * to emit JSON, and streaming JSON tokens to a UI is the wrong UX. Single non-streaming POST;
 * frontend shows a spinner during the ~1-2 second call.
 */
@RestController
@RequestMapping("/api/suspicious")
public class SuspiciousTriageController {

    private final SuspiciousBookingTriageService service;
    private final AiTriageProperties props;

    public SuspiciousTriageController(SuspiciousBookingTriageService service, AiTriageProperties props) {
        this.service = service;
        this.props = props;
    }

    /**
     * Triage a flagged suspicious booking. The {@code year}/{@code month} query params identify
     * which month's candidate set to search (the frontend already knows these from the URL of
     * the page that's rendering the Explain button).
     */
    @PostMapping("/{bookingId}/triage")
    public ResponseEntity<TriageResult> triage(@PathVariable String bookingId,
                                               @RequestParam int year,
                                               @RequestParam int month) {
        if (!props.isEnabled()) return ResponseEntity.notFound().build();
        return service.triage(bookingId, year, month)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Record the owner's explicit feedback on a cached triage. Looks up the current-prompt-version
     * triage row for this booking, updates its feedback columns, and ships a graded run to
     * LangSmith. Returns 404 when no triage exists for this booking (owner shouldn't be able to
     * trigger this, but be defensive).
     */
    @PostMapping("/{bookingId}/triage/feedback")
    public ResponseEntity<Void> feedback(@PathVariable String bookingId,
                                         @RequestBody FeedbackRequest body) {
        if (!props.isEnabled()) return ResponseEntity.notFound().build();
        boolean recorded = service.recordFeedback(bookingId, body.helpful(), body.correctedClassification());
        return recorded ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    public record FeedbackRequest(boolean helpful, TriageClassification correctedClassification) {}
}
