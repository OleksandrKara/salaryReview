package com.salonreview.sms;

import com.salonreview.domain.Business;

/**
 * Destinations for the checkout-review-request automation's two reply branches — owner-set per
 * business ({@link Business#getGoogleReviewUrl()}/{@link Business#getFeedbackFormUrl()}), not
 * code-level config, so a new business never inherits another salon's Google listing or feedback
 * form. See openspec/changes/sms-automations-hub.
 */
public final class CheckoutReviewLinks {

    public static final String GOOGLE_REVIEW_TARGET = "GOOGLE_REVIEW";
    public static final String FEEDBACK_FORM_TARGET = "FEEDBACK_FORM";

    private CheckoutReviewLinks() {
    }

    /** {@code null} if {@code linkTarget} isn't one of the two known values, or if the business
     * hasn't set that particular URL yet (see {@code CheckoutReviewTriggerService}, which is
     * expected to have already skipped creating the flow in that case — this is a second,
     * independent line of defense for any link generated before that gate existed). */
    public static String resolve(String linkTarget, Business business) {
        if (business == null) return null;
        if (GOOGLE_REVIEW_TARGET.equals(linkTarget)) return blankToNull(business.getGoogleReviewUrl());
        if (FEEDBACK_FORM_TARGET.equals(linkTarget)) return blankToNull(business.getFeedbackFormUrl());
        return null;
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
