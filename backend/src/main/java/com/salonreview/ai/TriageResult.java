package com.salonreview.ai;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.salonreview.domain.SuspiciousTriage;
import com.salonreview.domain.TriageClassification;

import java.math.BigDecimal;
import java.util.List;

/**
 * Structured output the LLM returns for a flagged suspicious booking. The Anthropic Java SDK
 * derives a JSON Schema from this record's shape and constrains the model's output to match —
 * meaning {@link #classification} is always one of three enum values, {@link #confidence} is always
 * a number in [0.0, 1.0], and no extra fields ever appear in the response. The model cannot return
 * malformed JSON or out-of-schema strings.
 *
 * <p>This is the carry-around shape used by the service, controller, and frontend. Persistence
 * uses {@link com.salonreview.domain.SuspiciousTriage}; this record is what the cache layer
 * builds from a DB row or a fresh LLM call.
 */
public record TriageResult(

        @JsonPropertyDescription("Classification of the suspicious booking — exactly one of the three enum values.")
        TriageClassification classification,

        @JsonPropertyDescription(
                "How much evidence supports the classification, between 0.0 and 1.0. If signals are weak, "
                        + "contradictory, or the booking context is ambiguous, set confidence below 0.5 and choose "
                        + "NEEDS_REVIEW rather than guessing.")
        BigDecimal confidence,

        @JsonPropertyDescription(
                "2-3 sentence plain-English explanation citing the specific detection signals that fired. "
                        + "Address the owner directly. No marketing copy, no hedging filler.")
        String explanation,

        @JsonPropertyDescription(
                "Professional 1-3 sentence message the owner can copy/paste to send the provider. "
                        + "Polite, factual, asks a question rather than accuses. Empty string when classification is LIKELY_LEGIT.")
        String draftMessage,

        @JsonPropertyDescription(
                "List of detection-signal names the explanation cites (e.g. [\"past_appointment_no_order\", \"zero_tip\"]).")
        List<String> signals,

        @JsonPropertyDescription(
                "Prompt version that produced this triage; populated by the service after the LLM call, not by the model.")
        String promptVersion,

        @JsonPropertyDescription(
                "Model identifier (e.g. claude-haiku-4-5) that produced this triage; populated by the service after the LLM call, not by the model.")
        String model
) {
    /** Build a {@code TriageResult} from a persisted {@link SuspiciousTriage} row. */
    public static TriageResult fromEntity(SuspiciousTriage t) {
        return new TriageResult(
                t.getClassification(),
                t.getConfidence(),
                t.getExplanation(),
                t.getDraftMessage(),
                t.getSignals(),
                t.getPromptVersion(),
                t.getModel());
    }
}
