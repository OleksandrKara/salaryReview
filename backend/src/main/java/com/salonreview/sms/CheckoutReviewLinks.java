package com.salonreview.sms;

/**
 * Fixed destination URLs for the checkout-review-request automation's two reply branches —
 * small, code-level config, not owner-editable copy, matching every other "no CMS" convention in
 * this codebase (owner-provided directly, see openspec/changes/sms-automations-hub).
 */
public final class CheckoutReviewLinks {

    public static final String GOOGLE_REVIEW_TARGET = "GOOGLE_REVIEW";
    public static final String FEEDBACK_FORM_TARGET = "FEEDBACK_FORM";

    public static final String GOOGLE_REVIEW_URL = "https://g.page/r/CY0ZQsqUPmkaEBM/review";
    public static final String FEEDBACK_FORM_URL = "https://forms.gle/53FQHGUWJUhkuRaW7";

    private CheckoutReviewLinks() {
    }

    /** {@code null} if {@code linkTarget} isn't one of the two known values. */
    public static String resolve(String linkTarget) {
        if (GOOGLE_REVIEW_TARGET.equals(linkTarget)) return GOOGLE_REVIEW_URL;
        if (FEEDBACK_FORM_TARGET.equals(linkTarget)) return FEEDBACK_FORM_URL;
        return null;
    }
}
