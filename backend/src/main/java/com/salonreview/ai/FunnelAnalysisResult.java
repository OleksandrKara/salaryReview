package com.salonreview.ai;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.salonreview.domain.FunnelAnalysis;
import com.salonreview.domain.ImpactLevel;

import java.util.List;

/**
 * Structured output the LLM returns for a booking funnel. The Anthropic Java SDK derives a JSON
 * Schema from this record's shape and constrains the model's output to match — same mechanism as
 * {@link TriageResult}, applied to a CRO-consultant task instead of a fraud classifier.
 *
 * <p>This is the carry-around shape used by the service, controller, and frontend. Persistence
 * uses {@link FunnelAnalysis}; this record is what the cache layer builds from a DB row or a
 * fresh LLM call.
 */
public record FunnelAnalysisResult(

        @JsonPropertyDescription(
                "The step_key of the single biggest bottleneck in this funnel — the step with the most "
                        + "impactful drop-off, not necessarily the highest raw dropOffCount if a later step "
                        + "with fewer absolute users represents a worse *rate*.")
        String biggestBottleneckStep,

        @JsonPropertyDescription(
                "2-4 plain sentences explaining why this step is the bottleneck, citing the actual "
                        + "numbers (counts and percentages) from the funnel data provided. Address the owner "
                        + "directly. No marketing filler, no hedging.")
        String bottleneckExplanation,

        @JsonPropertyDescription(
                "2-5 concrete, prioritized recommendations to improve conversion, ordered highest-impact "
                        + "first. Each must be specific to this funnel's actual data, not generic CRO advice.")
        List<PrioritizedRecommendation> recommendations,

        @JsonPropertyDescription(
                "Any suspicious patterns or anomalies in the numbers worth flagging (e.g. an "
                        + "implausibly high or low rate, a step with more sessions than the one before it, "
                        + "a conversion rate that seems inconsistent with the step data). Empty list if "
                        + "nothing looks anomalous — do not invent a concern that isn't there.")
        List<String> suspiciousPatterns,

        @JsonPropertyDescription(
                "1-3 specific A/B test ideas that directly address the identified bottleneck(s) — each "
                        + "one sentence, concrete enough to actually build (e.g. \"Move contact-info "
                        + "collection to the last step instead of the first\"), not vague ideas like "
                        + "\"improve UX\".")
        List<String> suggestedAbTests,

        @JsonPropertyDescription(
                "One sentence: the single highest-priority optimization to work on next, and why it beats "
                        + "the other recommendations listed above.")
        String topPriorityAction,

        @JsonPropertyDescription(
                "Prompt version that produced this analysis; populated by the service after the LLM call, not by the model.")
        String promptVersion,

        @JsonPropertyDescription(
                "Model identifier (e.g. claude-sonnet-5) that produced this analysis; populated by the service after the LLM call, not by the model.")
        String model
) {
    public record PrioritizedRecommendation(

            @JsonPropertyDescription("Short, specific title for the recommendation (under 10 words).")
            String title,

            @JsonPropertyDescription(
                    "1-3 sentences explaining why this recommendation follows from the funnel data — cite "
                            + "the specific step/numbers that motivate it.")
            String rationale,

            @JsonPropertyDescription(
                    "Expected impact on overall conversion if implemented: HIGH, MEDIUM, or LOW.")
            ImpactLevel expectedImpact
    ) {}

    /** Build a {@code FunnelAnalysisResult} from a persisted {@link FunnelAnalysis} row. */
    public static FunnelAnalysisResult fromEntity(FunnelAnalysis a) {
        return new FunnelAnalysisResult(
                a.getBiggestBottleneckStep(),
                a.getBottleneckExplanation(),
                a.getRecommendations(),
                a.getSuspiciousPatterns(),
                a.getSuggestedAbTests(),
                a.getTopPriorityAction(),
                a.getPromptVersion(),
                a.getModel());
    }
}
