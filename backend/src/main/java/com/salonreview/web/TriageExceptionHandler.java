package com.salonreview.web;

import com.salonreview.ai.SuspiciousBookingTriageService.TriageFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Maps AI triage failures (Anthropic API errors, schema-validation failures, anything thrown out
 * of {@link com.salonreview.ai.SuspiciousBookingTriageService}) to a clean 502 with a generic
 * user-facing message. We never leak raw model output, stack traces, or upstream error bodies to
 * the browser — that's been a real source of prod incidents elsewhere.
 *
 * <p>Scoped via {@link Order} ahead of any generic global handlers. The exception type is
 * specific to triage, so this advice doesn't accidentally swallow unrelated 500s.
 */
@RestControllerAdvice(basePackages = "com.salonreview.web")
@Order(0)
public class TriageExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(TriageExceptionHandler.class);
    private static final String GENERIC_MESSAGE =
            "AI explanation unavailable; please try again or review manually.";
    private static final String OUT_OF_CREDITS_MESSAGE =
            "The AI explainer is paused — the salon's Anthropic API credits need to be refilled. "
                    + "Please contact your salon admin.";

    @ExceptionHandler(TriageFailedException.class)
    public ResponseEntity<Map<String, String>> handleTriageFailed(TriageFailedException e) {
        // Detailed cause logged server-side; user sees only a curated message.
        log.error("Triage failed (502 to client): {}", e.toString(), e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", userMessageFor(e)));
    }

    /**
     * Match the cause chain against known Anthropic error shapes to surface a more actionable
     * message to the owner. Falls back to the generic message for anything we don't recognize.
     * Add new branches here as new failure modes show up in production.
     */
    private static String userMessageFor(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String msg = t.getMessage();
            if (msg == null) continue;
            // Anthropic returns this exact phrase on a 400 when the account balance is exhausted.
            if (msg.contains("credit balance is too low")) return OUT_OF_CREDITS_MESSAGE;
        }
        return GENERIC_MESSAGE;
    }
}
