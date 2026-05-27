package com.salonreview.commission;

/**
 * Which point in the month-aware true-up a settlement represents.
 *
 * <p>The first half is paid conservatively (always the base rate) so a provider is never overpaid
 * and therefore never owes money back. The month-close settlement reconciles the whole month and
 * adds a tier bonus if the service threshold was met.
 */
public enum Stage {
    /** First-half (1-15) payout, computed at the base rate with no tier bonus yet. */
    PROVISIONAL_FIRST_HALF,
    /** Second-half (16-end) payout, including the whole-month tier bonus when qualified. */
    FINAL_MONTH_CLOSE
}
