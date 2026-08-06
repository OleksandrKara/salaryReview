package com.salonreview.sms;

import com.salonreview.util.Names;
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
            /** Team-voiced, not signed by a named individual — this confirmation doesn't promise a
             * phone call (the salon follows up by text in practice, not by calling), so it says
             * "text you" rather than the previous "I'll call you". */
            "four_hand_request_received", new SmsTemplate(
                    "four_hand_request_received",
                    SmsMessageClass.TRANSACTIONAL,
                    "four_hand_request",
                    vars -> render("Hi {{name}}! Got your 4-Hand request for {{preferredTime}} 💛 "
                            + "Our team will text you shortly to confirm timing & pricing! -AK.LUX.NAILS", vars)
            ),
            /** One-off operational check, not a customer-facing message — TRANSACTIONAL so it isn't
             * blocked by a marketing-consent lookup that would never find the owner's own number. */
            "toll_free_live_test", new SmsTemplate(
                    "toll_free_live_test",
                    SmsMessageClass.TRANSACTIONAL,
                    vars -> render("This is the first message from AK.LUX.NAILS' new toll-free number. "
                            + "Twilio verification approved and live!", vars)
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
                        String name = Names.capitalizeFirst(vars == null ? null : vars.get("name"));
                        String technician = vars == null ? null : vars.get("technician");
                        String greeting = (name == null || name.isBlank()) ? "Hi!" : "Hi " + name + "!";
                        if (technician != null && !technician.isBlank()) {
                            return greeting + " It's Lucy 💛 You were with " + technician
                                    + " today. How'd we do? Just reply with a number, 1 to 5!";
                        }
                        return greeting + " It's Lucy from AK.LUX.NAILS 💛 I like to personally check in "
                                + "after every visit. How'd we do? Just reply with a number, 1 to 5!";
                    }
            ),
            /** Sent by {@code LeadFollowUpScheduler} 2 minutes after a lead leaves contact info
             * with no upcoming appointment — see openspec/changes/lead-followup-and-manager-inbox
             * design.md D4. Purely helpful, no discount/incentive — TRANSACTIONAL, sendable
             * regardless of marketing consent. No {{name}} falls back to a name-less greeting.
             * No link, deliberately — everyone who gets this just came from the site itself, so
             * re-sending its own URL back to them adds nothing; reply-only keeps this short.
             * Deliberately doesn't guess *why* they didn't book (could be timing, price, wrong
             * service) — asking two open questions covers all of those without assuming it was
             * a scheduling problem specifically. */
            "lead_follow_up_nudge", new SmsTemplate(
                    "lead_follow_up_nudge",
                    SmsMessageClass.TRANSACTIONAL,
                    "lead_follow_up",
                    vars -> {
                        String name = Names.capitalizeFirst(vars == null ? null : vars.get("name"));
                        String greeting = (name == null || name.isBlank()) ? "Hi!" : "Hi " + name + "!";
                        return greeting + " It's Lucy from AK.LUX.NAILS 💛 Do you need help with more "
                                + "openings or is there anything specific you are looking for?";
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
            String key = matcher.group(1);
            String value = variables.getOrDefault(key, "");
            // Normalized here, once, so every render()-based template gets it for free rather
            // than each caller remembering to — see Names' own doc comment.
            if ("name".equals(key)) {
                value = Names.capitalizeFirst(value);
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
