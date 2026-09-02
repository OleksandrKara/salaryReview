package com.salonreview.ai;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.salonreview.domain.ImpactLevel;
import com.salonreview.domain.SeoAnalysis;

import java.time.Instant;
import java.util.List;

/**
 * Structured output the LLM returns for an SEO analysis. The Anthropic Java SDK derives a JSON
 * Schema from this record's shape and constrains the model's output to match — same mechanism as
 * {@link FunnelAnalysisResult}, applied to an SEO-consultant task instead of a CRO one.
 *
 * <p>This is the carry-around shape used by the service, controller, and frontend. Persistence
 * uses {@link SeoAnalysis}; this record is what the cache layer builds from a DB row or a fresh
 * LLM call. Per the proposal's own "tell me what's happening, why it matters, and what to do
 * next" principle — never just a restatement of the numbers already visible on the dashboard.
 */
public record SeoAnalysisResult(

        @JsonPropertyDescription(
                "Overall SEO health in one word: HEALTHY, NEEDS_ATTENTION, or CRITICAL — a defensible "
                        + "judgment call from the data provided (open technical issues, significant losses "
                        + "outweighing wins, etc.), not a vague feel-good default.")
        OverallStatus overallStatus,

        @JsonPropertyDescription(
                "2-4 plain sentences: what's actually happening with this business's SEO right now. "
                        + "Address the owner directly. No marketing filler, no hedging, cite real numbers "
                        + "from the data provided.")
        String executiveSummary,

        @JsonPropertyDescription(
                "3-5 concrete positive changes, each one sentence citing the actual evidence (a keyword's "
                        + "position move, a page's impression growth, etc.). Empty list if nothing "
                        + "meaningful improved — do not invent a win.")
        List<String> wins,

        @JsonPropertyDescription(
                "3-5 concrete negative changes or open technical issues, each one sentence citing actual "
                        + "evidence. Empty list if nothing meaningful got worse — do not invent a problem.")
        List<String> problems,

        @JsonPropertyDescription(
                "3-8 prioritized, actionable recommendations, ranked by expected business impact — the "
                        + "single most important one first. Each must follow from data actually provided "
                        + "(a specific keyword, page, or technical issue), never a generic SEO best practice "
                        + "recommended just because it's usually good advice.")
        List<Recommendation> recommendations,

        @JsonPropertyDescription(
                "Prompt version that produced this analysis; populated by the service after the LLM call, not by the model.")
        String promptVersion,

        @JsonPropertyDescription(
                "Model identifier (e.g. claude-sonnet-5) that produced this analysis; populated by the service after the LLM call, not by the model.")
        String model,

        @JsonPropertyDescription(
                "When this analysis was generated; populated by the service after persisting, not by the model.")
        Instant createdAt
) {
    public enum OverallStatus {
        HEALTHY, NEEDS_ATTENTION, CRITICAL
    }

    public record Recommendation(

            @JsonPropertyDescription("1-10: how important this is relative to the other recommendations in this "
                    + "same list, 1 being the single highest priority.")
            int priority,

            @JsonPropertyDescription("Short, specific title for the action (under 12 words) — e.g. \"Improve "
                    + "the Russian Manicure landing page's title tag\".")
            String action,

            @JsonPropertyDescription(
                    "1-3 sentences: why this action matters MORE than the other candidate actions RIGHT NOW "
                            + "— not just that it's generally good SEO practice. Compare it against at least one "
                            + "other plausible action when the data supports that comparison.")
            String why,

            @JsonPropertyDescription(
                    "The specific data point(s) that justify this recommendation — real numbers (impressions, "
                            + "position, clicks, CTR) from the snapshot provided, not a vague reference.")
            String evidence,

            @JsonPropertyDescription("Expected impact if implemented: HIGH, MEDIUM, or LOW.")
            ImpactLevel expectedImpact,

            @JsonPropertyDescription("Rough implementation effort: HIGH, MEDIUM, or LOW.")
            ImpactLevel effort,

            @JsonPropertyDescription("How confident this recommendation is, given the data available: HIGH, "
                    + "MEDIUM, or LOW — lower confidence for thin/noisy data, e.g. very low impressions.")
            ImpactLevel confidence,

            @JsonPropertyDescription(
                    "1-2 concrete sentences on how to actually implement this (e.g. \"Rewrite the title tag "
                            + "to lead with 'Russian Manicure' instead of the business name\"), not a vague "
                            + "restatement of the action.")
            String suggestedImplementation,

            @JsonPropertyDescription(
                    "The specific page URL or keyword this recommendation is about, taken verbatim from the "
                            + "data provided — null if the recommendation is genuinely site-wide (e.g. a Core "
                            + "Web Vitals issue affecting every page).")
            String relevantPageOrKeyword
    ) {}

    /** Build a {@code SeoAnalysisResult} from a persisted {@link SeoAnalysis} row. */
    public static SeoAnalysisResult fromEntity(SeoAnalysis a) {
        return new SeoAnalysisResult(
                a.getOverallStatus(),
                a.getExecutiveSummary(),
                a.getWins(),
                a.getProblems(),
                a.getRecommendations(),
                a.getPromptVersion(),
                a.getModel(),
                a.getCreatedAt());
    }
}
