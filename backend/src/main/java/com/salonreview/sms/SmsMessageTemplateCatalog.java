package com.salonreview.sms;

import java.util.List;
import java.util.Map;

/**
 * Every SMS body an automation can send, as an owner-editable {@code {{variable}}} template with
 * its in-code default text — the fallback used whenever no {@link com.salonreview.domain.
 * SmsTemplateOverride} row exists for a business. See {@code SmsMessageTemplateService} for the
 * override-or-default resolution and variable substitution.
 *
 * <p>{@code messageClass} (transactional vs. marketing, which gates the marketing-consent check)
 * is fixed here per key and never accepted from an owner edit — same safety property {@link
 * SmsTemplateRegistry}'s own doc comment already established for the templates it covers. Only the
 * wording is editable.
 *
 * <p>A branch that used to pick between two hand-written sentences (e.g. "with a named technician"
 * vs. "no technician on file") is split into two separate keys here rather than exposed as one
 * template with a raw {@code {{technician}}} slot — the two sentences differ in structure, not
 * just a fill-in-the-blank word, so editing them independently is what an owner actually wants.
 * Where the difference really is just an inserted clause (e.g. same_day_rebooking's "want to lock
 * in your next spot[ with Sarah]"), the caller pre-computes that clause as its own variable
 * (documented per key below) instead of forcing a second key.
 */
public final class SmsMessageTemplateCatalog {

    public record TemplateDefault(String key, String automationKey, SmsMessageClass messageClass,
                                   String label, String defaultBody, List<String> variables) {}

    private static final Map<String, TemplateDefault> DEFAULTS = Map.ofEntries(
            Map.entry("four_hand_request_received", new TemplateDefault(
                    "four_hand_request_received", "four_hand_request", SmsMessageClass.TRANSACTIONAL,
                    "Request confirmation",
                    "Hi {{name}}! Got your 4-Hand request for {{preferredTime}} 💛 Our team will text you "
                            + "shortly to confirm timing & pricing! -{{businessName}}",
                    List.of("name", "preferredTime", "businessName")
            )),
            Map.entry("checkout_rating_request_with_technician", new TemplateDefault(
                    "checkout_rating_request_with_technician", "checkout_review_request", SmsMessageClass.TRANSACTIONAL,
                    "Rating request (technician known)",
                    "{{greeting}} It's {{sender}} 💛 You were with {{technician}} today. How'd we do? "
                            + "Just reply with a number, 1 to 5!",
                    List.of("greeting", "sender", "technician")
            )),
            Map.entry("checkout_rating_request_no_technician", new TemplateDefault(
                    "checkout_rating_request_no_technician", "checkout_review_request", SmsMessageClass.TRANSACTIONAL,
                    "Rating request (technician unknown)",
                    "{{greeting}} It's {{sender}} from {{businessName}} 💛 I like to personally check in after "
                            + "every visit. How'd we do? Just reply with a number, 1 to 5!",
                    List.of("greeting", "sender", "businessName")
            )),
            Map.entry("checkout_review_positive", new TemplateDefault(
                    "checkout_review_positive", "checkout_review_request", SmsMessageClass.TRANSACTIONAL,
                    "5-star reply: ask for a Google review",
                    "Yay, so happy to hear that! 🎉 Since you loved it, mind leaving a quick Google review? "
                            + "Takes 10 seconds and really helps our small business: {{link}} -{{sender}}",
                    List.of("link", "sender")
            )),
            Map.entry("checkout_review_positive_repeat", new TemplateDefault(
                    "checkout_review_positive_repeat", "checkout_review_request", SmsMessageClass.TRANSACTIONAL,
                    "5-star reply: already reviewed before",
                    "So glad you loved it again! 💕 You've already clicked through to leave us a Google review "
                            + "before. If there's any specific feedback this time, we'd love to hear it here: "
                            + "{{link}} -{{businessName}}",
                    List.of("link", "businessName")
            )),
            Map.entry("checkout_review_negative", new TemplateDefault(
                    "checkout_review_negative", "checkout_review_request", SmsMessageClass.TRANSACTIONAL,
                    "Low-rating reply: service recovery",
                    "I'm really sorry today wasn't a 5 for you 💛 I'd love to make it right personally. Reply "
                            + "and tell me what happened, or share details here if that's easier: {{link}} "
                            + "-{{sender}}, Manager",
                    List.of("link", "sender")
            )),
            Map.entry("lead_follow_up_nudge", new TemplateDefault(
                    "lead_follow_up_nudge", "lead_follow_up", SmsMessageClass.TRANSACTIONAL,
                    "Lead follow-up",
                    "{{greeting}} It's {{sender}} from {{businessName}} 💛 Do you need help with more openings or "
                            + "is there anything specific you are looking for?",
                    List.of("greeting", "sender", "businessName")
            )),
            // spotClause is pre-computed by the caller: "want to lock in your next spot" or
            // "want to lock in your next spot with {technician}" — see class doc on why this is a
            // clause variable rather than a second key.
            Map.entry("same_day_rebooking_nudge", new TemplateDefault(
                    "same_day_rebooking_nudge", "same_day_rebooking_discount", SmsMessageClass.MARKETING,
                    "Same-day nudge (consented)",
                    "Hope you're loving your nails 💛 Since you're already here today, {{spotClause}}? "
                            + "I'll knock {{discountAmount}} off if you book before midnight: {{link}}",
                    List.of("spotClause", "discountAmount", "link")
            )),
            // urgencyClause is pre-computed: "Spots are filling up fast this time of year" or
            // "{technician}'s spots are filling up fast this time of year".
            Map.entry("same_day_rebooking_reminder", new TemplateDefault(
                    "same_day_rebooking_reminder", "same_day_rebooking_discount", SmsMessageClass.TRANSACTIONAL,
                    "Same-day nudge (no consent on file)",
                    "{{urgencyClause}} 💛 Might be worth grabbing your next one today instead of the usual "
                            + "3-4 week wait: {{link}}",
                    List.of("urgencyClause", "link")
            )),
            // offerClause is pre-computed: "It's been 3+ weeks since your last visit. Spots are
            // filling up fast right now, grabbed you $5 off if you book today" or the same with
            // "and {technician}'s schedule is almost full" inserted.
            Map.entry("lapsed_customer_winback_nudge", new TemplateDefault(
                    "lapsed_customer_winback_nudge", "lapsed_customer_winback", SmsMessageClass.MARKETING,
                    "Lapsed win-back (consented)",
                    "{{greeting}} It's {{sender}} from {{businessName}} 💛 {{offerClause}}: {{link}} -{{sender}}",
                    List.of("greeting", "sender", "offerClause", "link", "businessName")
            )),
            // offerClause here: "It's been 3+ weeks since your last visit. Spots are filling up
            // fast right now, want me to grab you a spot" (or with technician's schedule inserted).
            Map.entry("lapsed_customer_winback_reminder", new TemplateDefault(
                    "lapsed_customer_winback_reminder", "lapsed_customer_winback", SmsMessageClass.TRANSACTIONAL,
                    "Lapsed win-back (no consent on file)",
                    "{{greeting}} It's {{sender}} from {{businessName}} 💛 {{offerClause}}? {{link}} -{{sender}}",
                    List.of("greeting", "sender", "offerClause", "link", "businessName")
            )),
            // visitClause is pre-computed: "It's been a while since your last visit" or "...with
            // {technician}".
            Map.entry("repeat_customer_winback_nudge_default", new TemplateDefault(
                    "repeat_customer_winback_nudge_default", "repeat_customer_winback", SmsMessageClass.MARKETING,
                    "Repeat win-back, same technician (consented)",
                    "{{greeting}} It's {{sender}} from {{businessName}} 💛 {{visitClause}}. Grabbed you "
                            + "{{discountAmount}} off if you book today: {{link}} -{{sender}}",
                    List.of("greeting", "sender", "visitClause", "discountAmount", "link", "businessName")
            )),
            Map.entry("repeat_customer_winback_reminder_default", new TemplateDefault(
                    "repeat_customer_winback_reminder_default", "repeat_customer_winback", SmsMessageClass.TRANSACTIONAL,
                    "Repeat win-back, same technician (no consent on file)",
                    "{{greeting}} It's {{sender}} from {{businessName}} 💛 {{visitClause}}. Book your next mani "
                            + "here: {{link}} -{{sender}}",
                    List.of("greeting", "sender", "visitClause", "link", "businessName")
            )),
            Map.entry("repeat_customer_winback_nudge_previous_provider", new TemplateDefault(
                    "repeat_customer_winback_nudge_previous_provider", "repeat_customer_winback", SmsMessageClass.MARKETING,
                    "Repeat win-back, technician changed (consented)",
                    "{{greeting}} It's {{sender}} from {{businessName}} 💛 It's been a while since we've seen "
                            + "you, want me to check if {{previousProvider}} has an opening for you? Grabbed "
                            + "you {{discountAmount}} off if you book today: {{link}} -{{sender}}",
                    List.of("greeting", "sender", "previousProvider", "discountAmount", "link", "businessName")
            )),
            Map.entry("repeat_customer_winback_reminder_previous_provider", new TemplateDefault(
                    "repeat_customer_winback_reminder_previous_provider", "repeat_customer_winback", SmsMessageClass.TRANSACTIONAL,
                    "Repeat win-back, technician changed (no consent on file)",
                    "{{greeting}} It's {{sender}} from {{businessName}} 💛 It's been a while since we've seen "
                            + "you, want me to check if {{previousProvider}} has an opening for you? Book "
                            + "here: {{link}} -{{sender}}",
                    List.of("greeting", "sender", "previousProvider", "link", "businessName")
            ))
    );

    private SmsMessageTemplateCatalog() {
    }

    /** {@code null} if the key isn't registered — a programmer error (an unknown key was never
     * assigned by a caller), not a data condition, so callers are expected to fail loudly. */
    public static TemplateDefault get(String key) {
        return DEFAULTS.get(key);
    }

    public static Map<String, TemplateDefault> all() {
        return DEFAULTS;
    }
}
