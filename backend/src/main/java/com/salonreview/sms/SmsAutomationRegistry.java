package com.salonreview.sms;

import java.util.Map;

/**
 * Static, code-level metadata (name + plain-English audience description) per automation key —
 * matches this codebase's "no CMS yet" convention (see {@link SmsTemplateRegistry}). The
 * enabled/disabled *state* itself is DB-backed ({@code sms_automation}, see
 * {@link com.salonreview.domain.SmsAutomation}) so the owner can toggle it; this class only
 * describes what each key means.
 */
public final class SmsAutomationRegistry {

    /** {@code primaryTemplateKey} disambiguates "how many times did this automation actually fire"
     * from the automation's total outbound row count, for the one automation
     * ({@code checkout_review_request}) that logs more than one distinct template under the same
     * {@code automationKey} — its conditional branch reply (Google review / feedback form) shares
     * the key but isn't itself a new firing of the automation. {@code null} for every other
     * automation, which only ever logs one template per key, so no filtering is needed.
     *
     * <p>{@code tracksClicks}/{@code tracksReplies} say whether a click-through rate / reply rate
     * is even a meaningful thing to show for this automation — see
     * {@code SmsAutomationService#list()}, which only queries those counts when true rather than
     * showing a misleading "0%" for an automation that never links or asks for a reply at all. */
    public record AutomationMeta(String key, String name, String audienceDescription,
                                  String primaryTemplateKey, boolean tracksClicks, boolean tracksReplies) {}

    private static final Map<String, AutomationMeta> META = Map.of(
            "four_hand_request", new AutomationMeta(
                    "four_hand_request",
                    "4-Hand request confirmation",
                    "Every customer who submits a 4-Hand manicure/pedicure request on mani or akluxnails-home",
                    null, false, false
            ),
            "checkout_review_request", new AutomationMeta(
                    "checkout_review_request",
                    "Post-checkout satisfaction request",
                    "Every customer who completes an in-salon checkout at the register — 2 minutes later, "
                            + "asked to rate their visit 1–5, then routed to a Google review or a private feedback form",
                    "checkout_rating_request", true, true
            ),
            "lead_follow_up", new AutomationMeta(
                    "lead_follow_up",
                    "Lead follow-up nudge",
                    "Every lead who leaves contact info but has no upcoming appointment 2 minutes later — "
                            + "a purely helpful, no-incentive text offering to help find a time",
                    null, false, false
            ),
            "same_day_rebooking_discount", new AutomationMeta(
                    "same_day_rebooking_discount",
                    "Same-day rebooking discount",
                    "Every in-salon checkout, 3 hours later, if they haven't already rebooked and have "
                            + "given SMS-marketing consent (in this app or in Square) — a $10-off nudge to "
                            + "rebook before midnight, min. $99 order",
                    null, true, false
            ),
            "lapsed_customer_winback", new AutomationMeta(
                    "lapsed_customer_winback",
                    "Lapsed customer win-back",
                    "Every customer with exactly one all-time visit, 21–35 days after that visit, if they "
                            + "haven't already rebooked — a one-time nudge naming their technician's own "
                            + "schedule; consented customers see a $5-off coupon (min. $99 order) valid until "
                            + "midnight that day, everyone else gets the same link with no discount language",
                    null, true, false
            )
    );

    private SmsAutomationRegistry() {
    }

    /** {@code null} if the key isn't a real automation (e.g. a one-off diagnostic template). */
    public static AutomationMeta describe(String automationKey) {
        return META.get(automationKey);
    }

    public static Map<String, AutomationMeta> all() {
        return META;
    }
}
