package com.salonreview.sms;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fixed, in-code template registry — no CMS, matching this codebase's existing convention for
 * owner-curated content that changes rarely (e.g. akluxnails-home's {@code services-config.ts}).
 * The point of keeping this in code (not DB-editable) is that {@link SmsMessageClass} is baked in
 * per template and reviewed here, in one place, rather than settable by whichever app calls the
 * send endpoint — see {@code openspec/changes/sms-automation-platform/design.md} D2.
 */
@Component
public class SmsTemplateRegistry {

    private static final Pattern VARIABLE = Pattern.compile("\\{\\{(\\w+)}}");

    private final Map<String, SmsTemplate> templates = Map.of(
            "four_hand_request_received", new SmsTemplate(
                    "four_hand_request_received",
                    SmsMessageClass.TRANSACTIONAL,
                    "four_hand_request",
                    vars -> render("Hi {{name}}! Got your 4-Hand request for {{preferredTime}} 💛 "
                            + "I'm Lucy, and I'll call you shortly to confirm timing & pricing — talk soon!", vars)
            ),
            /** One-off operational check, not a customer-facing message — TRANSACTIONAL so it isn't
             * blocked by a marketing-consent lookup that would never find the owner's own number. */
            "toll_free_live_test", new SmsTemplate(
                    "toll_free_live_test",
                    SmsMessageClass.TRANSACTIONAL,
                    vars -> render("This is the first message from AK.LUX.NAILS' new toll-free number "
                            + "— Twilio verification approved and live!", vars)
            ),
            /** Sent by {@code SmsReplyFlowScheduler} 2 minutes after an in-salon checkout — see
             * openspec/changes/sms-automations-hub design.md D5 for why this is TRANSACTIONAL (a
             * content-neutral, non-promotional same-day follow-up, not a marketing message). No
             * {{name}} falls back to a name-less greeting rather than rendering an empty "Hi ,".
             * {@code technician} (the display name of whoever handled the customer's most recent
             * visit — see {@code TechnicianNameResolver}) is optional: a resolution miss falls back
             * to a technician-less "I personally check in" framing rather than an empty mention. */
            "checkout_rating_request", new SmsTemplate(
                    "checkout_rating_request",
                    SmsMessageClass.TRANSACTIONAL,
                    "checkout_review_request",
                    vars -> {
                        String name = vars == null ? null : vars.get("name");
                        String technician = vars == null ? null : vars.get("technician");
                        String greeting = (name == null || name.isBlank()) ? "Hi!" : "Hi " + name + "!";
                        if (technician != null && !technician.isBlank()) {
                            return greeting + " It's Lucy 💛 You were with " + technician
                                    + " today — how'd we do, 1 to 5?";
                        }
                        return greeting + " It's Lucy from AK.LUX.NAILS 💛 I like to personally check in "
                                + "after every visit — how'd we do today, 1 to 5?";
                    }
            ),
            /** Sent by {@code LeadFollowUpScheduler} 2 minutes after a lead leaves contact info
             * with no upcoming appointment — see openspec/changes/lead-followup-and-manager-inbox
             * design.md D4. Purely helpful, no discount/incentive — TRANSACTIONAL, sendable
             * regardless of marketing consent. No {{name}} falls back to a name-less greeting.
             * {@code bookingLink} is the lead's own landing page (see MarketingLandingProperties) —
             * always present, never null, so there's no link-less fallback branch needed here
             * (contrast with the technician-name templates, where the lookup can genuinely miss). */
            "lead_follow_up_nudge", new SmsTemplate(
                    "lead_follow_up_nudge",
                    SmsMessageClass.TRANSACTIONAL,
                    "lead_follow_up",
                    vars -> {
                        String name = vars == null ? null : vars.get("name");
                        String bookingLink = vars == null ? null : vars.get("bookingLink");
                        String greeting = (name == null || name.isBlank()) ? "Hi!" : "Hi " + name + "!";
                        return greeting + " It's Lucy from AK.LUX.NAILS 💛 Saw you were checking us out — "
                                + "want me to hold you a spot? Easiest way is right here: " + bookingLink
                                + " (or just reply and I'll help!)";
                    }
            )
            // NOTE: "same_day_rebooking_nudge" is NOT registered here — like
            // CheckoutReviewReplyService's own branch replies, it needs a self-referencing
            // click-tracked link generated up front, so SameDayRebookingScheduler bypasses this
            // registry/TwilioSmsService.sendTemplated entirely and hand-renders its body, doing
            // its own (dual-source) consent check before ever sending — see
            // openspec/changes/same-day-rebooking-discount design.md D3/D5.
    );

    /** {@code null} if no template is registered under this key. */
    public SmsTemplate find(String key) {
        return templates.get(key);
    }

    private static String render(String template, Map<String, String> variables) {
        Matcher matcher = VARIABLE.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = variables.getOrDefault(matcher.group(1), "");
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
