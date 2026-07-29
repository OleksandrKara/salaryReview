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

    public record AutomationMeta(String key, String name, String audienceDescription) {}

    private static final Map<String, AutomationMeta> META = Map.of(
            "four_hand_request", new AutomationMeta(
                    "four_hand_request",
                    "4-Hand request confirmation",
                    "Every customer who submits a 4-Hand manicure/pedicure request on mani or akluxnails-home"
            ),
            "checkout_review_request", new AutomationMeta(
                    "checkout_review_request",
                    "Post-checkout satisfaction request",
                    "Every customer who completes an in-salon checkout at the register — 2 minutes later, "
                            + "asked to rate their visit 1–5, then routed to a Google review or a private feedback form"
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
