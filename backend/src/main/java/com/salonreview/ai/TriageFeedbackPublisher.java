package com.salonreview.ai;

import com.salonreview.domain.TriageClassification;

/**
 * Hook that lets the existing suspicious-bookings clearance flow ship implicit feedback to
 * LangSmith without depending on the AI module directly. The bean is only registered when the
 * AI feature is enabled; callers should inject {@code ObjectProvider<TriageFeedbackPublisher>}
 * and {@code ifAvailable(...)} so the existing flow boots cleanly when AI is off.
 */
public interface TriageFeedbackPublisher {

    /**
     * Called after an owner Clears a flagged booking. If a cached triage exists for this booking,
     * ship a LangSmith feedback event scoring agreement between the LLM classification and the
     * owner's clearance action.
     */
    void onClear(String bookingId, String username, String note);

    /** Called after an owner Undoes a clearance. Ships a retraction-style event to LangSmith. */
    void onUnclear(String bookingId);

    /**
     * Called by the triage controller when the owner records explicit thumbs-up / thumbs-down
     * feedback (with an optional corrected classification) on a triage card.
     */
    void onExplicitFeedback(Long triageId, boolean helpful, TriageClassification correctedClassification);
}
