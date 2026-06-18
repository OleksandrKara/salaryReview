package com.salonreview.ai;

import com.salonreview.domain.SuspiciousTriage;
import com.salonreview.domain.TriageClassification;
import com.salonreview.repo.SuspiciousTriageRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Default impl of {@link TriageFeedbackPublisher} — wires Clear/Undo/explicit-feedback actions
 * back to LangSmith as graded runs, building the labeled eval dataset that powers prompt
 * regression tests.
 *
 * <p>Score convention for implicit feedback (Clear action):
 * <ul>
 *   <li>LLM classified LIKELY_LEGIT → owner Cleared → agreement → score 1.0.</li>
 *   <li>LLM classified NEEDS_REVIEW → owner Cleared → owner decided where the LLM didn't → 0.5.</li>
 *   <li>LLM classified LIKELY_FRAUD → owner Cleared → disagreement → score 0.0.</li>
 * </ul>
 * Notes from the clearance row (e.g. {@code "refund"}) are passed in {@code metadata} so the
 * LangSmith dashboard can surface them next to the score.
 */
@Component
@ConditionalOnProperty(prefix = "ai.triage", name = "enabled", havingValue = "true")
public class DefaultTriageFeedbackPublisher implements TriageFeedbackPublisher {

    private final SuspiciousTriageRepository triages;
    private final LangSmithTracer tracer;

    public DefaultTriageFeedbackPublisher(SuspiciousTriageRepository triages, LangSmithTracer tracer) {
        this.triages = triages;
        this.tracer = tracer;
    }

    @Override
    public void onClear(String bookingId, String username, String note) {
        currentTriage(bookingId).ifPresent(t -> {
            double score = switch (t.getClassification()) {
                case LIKELY_LEGIT -> 1.0;
                case NEEDS_REVIEW -> 0.5;
                case LIKELY_FRAUD -> 0.0;
            };
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("owner_action", "clear");
            metadata.put("owner_username", username);
            if (note != null && !note.isBlank()) metadata.put("note", note);
            metadata.put("llm_classification", t.getClassification().name());
            tracer.feedback(t.getLangsmithRunId(), score, "owner_clear_action", metadata);
        });
    }

    @Override
    public void onUnclear(String bookingId) {
        currentTriage(bookingId).ifPresent(t -> {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("owner_action", "unclear");
            metadata.put("llm_classification", t.getClassification().name());
            // Use 0.5 as a neutral marker — the owner retracted; treat as "no signal" for eval purposes.
            tracer.feedback(t.getLangsmithRunId(), 0.5, "owner_unclear_action", metadata);
        });
    }

    @Override
    public void onExplicitFeedback(Long triageId, boolean helpful, TriageClassification corrected) {
        triages.findById(triageId).ifPresent(t -> {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("owner_action", helpful ? "thumbs_up" : "thumbs_down");
            metadata.put("llm_classification", t.getClassification().name());
            if (corrected != null) metadata.put("corrected_classification", corrected.name());
            tracer.feedback(t.getLangsmithRunId(), helpful ? 1.0 : 0.0,
                    "owner_explicit_feedback", metadata);
        });
    }

    /**
     * Look up the triage row for this booking under the *current* prompt version — that's the
     * row the owner just saw. Older versions exist in the table for eval comparison but they're
     * not what produced the triage card the owner reacted to.
     */
    private Optional<SuspiciousTriage> currentTriage(String bookingId) {
        return triages.findBySquareBookingIdAndPromptVersion(bookingId, TriagePrompts.PROMPT_VERSION);
    }
}
