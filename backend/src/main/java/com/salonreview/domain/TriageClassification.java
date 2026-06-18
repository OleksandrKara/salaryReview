package com.salonreview.domain;

/**
 * Three-way classification the LLM assigns to a flagged suspicious booking. Persisted as a string
 * so the column stays human-readable in adminer and survives enum reorderings.
 *
 * <ul>
 *   <li>{@link #LIKELY_LEGIT} — owner can reasonably Clear; the missing money trail probably has a
 *       benign explanation (refund, comp, owner forgot to record).</li>
 *   <li>{@link #NEEDS_REVIEW} — signals are mixed; owner should investigate before deciding.</li>
 *   <li>{@link #LIKELY_FRAUD} — multiple signals fired with no benign explanation; owner should
 *       consider sending the drafted message to the provider.</li>
 * </ul>
 */
public enum TriageClassification {
    LIKELY_LEGIT,
    NEEDS_REVIEW,
    LIKELY_FRAUD,
}
