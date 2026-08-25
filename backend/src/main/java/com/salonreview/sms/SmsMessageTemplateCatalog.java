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
 *
 * <p>{@code defaultBodies} — more than one entry for the checkout-review-request/same-day-
 * rebooking-discount keys a repeat regular sees on essentially every visit (rating request,
 * 5-star/low-rating replies, the same-day rebook offer). A loyal weekly/biweekly client getting
 * the exact same wording every single time is what actually reads as "this is a bot," not the
 * fact that it's automated — {@link SmsMessageTemplateService#render} rotates through these
 * deterministically per (business, phone, template) by how many times that customer has already
 * been sent this exact template, so the same regular sees different wording each visit instead of
 * an identical script, without ever repeating variant N until N sends later. Every other key here
 * still has exactly one body (a single-element list) — those either don't repeat often enough for
 * a customer to notice (lead follow-up, win-back) or are one-offs (4-hand confirmation). An
 * owner's {@link com.salonreview.domain.SmsTemplateOverride}, once saved, replaces every variant
 * with that one wording — variant rotation only applies to the in-code default.
 */
public final class SmsMessageTemplateCatalog {

    public record TemplateDefault(String key, String automationKey, SmsMessageClass messageClass,
                                   String label, List<String> defaultBodies, List<String> variables) {

        /** The first variant — used wherever only a single representative body makes sense (the
         * template editor's starting text, {@code resetToDefault}'s returned view). Actual sends
         * pick among all of {@link #defaultBodies} — see this class's own doc. */
        public String defaultBody() {
            return defaultBodies.get(0);
        }
    }

    private static final Map<String, TemplateDefault> DEFAULTS = Map.ofEntries(
            Map.entry("four_hand_request_received", new TemplateDefault(
                    "four_hand_request_received", "four_hand_request", SmsMessageClass.TRANSACTIONAL,
                    "Request confirmation",
                    List.of("Hi {{name}}! Got your 4-Hand request for {{preferredTime}} 💛 Our team will text you "
                            + "shortly to confirm timing & pricing! -{{businessName}}"),
                    List.of("name", "preferredTime", "businessName")
            )),
            // Same shape as four_hand_request_received (a booking-confirmation text, not a reply-
            // ask) — added because Square's own confirmation text doesn't reliably fire for a PMU
            // consultation booking (Business 2 automation #1). Relayed through the same
            // businessId-parameterized /api/internal/notifications/sms/send endpoint, not a new
            // one — see InternalNotificationController's own doc.
            // detailsClause is pre-computed by the caller (salonLandings): "We'll call you at
            // {time}!" for an online consultation, "We'll be waiting for you at {address} at
            // {time}!" for an in-person one — different enough in structure between the two that
            // a single {{preferredTime}}-only slot can't express it, same "caller pre-computes the
            // clause" convention this class's own doc already establishes for same_day_rebooking's
            // spotClause.
            Map.entry("consultation_request_confirmation", new TemplateDefault(
                    "consultation_request_confirmation", "consultation_lead_sms", SmsMessageClass.TRANSACTIONAL,
                    "Consultation confirmation",
                    List.of("Hi {{name}}! Your consultation with {{businessName}} is confirmed 💛 {{detailsClause}}"),
                    List.of("name", "businessName", "detailsClause")
            )),
            Map.entry("checkout_rating_request_with_technician", new TemplateDefault(
                    "checkout_rating_request_with_technician", "checkout_review_request", SmsMessageClass.TRANSACTIONAL,
                    "Rating request (technician known)",
                    List.of(
                            "{{greeting}} It's {{sender}} 💛 You were with {{technician}} today. How'd we do? "
                                    + "Just reply with a number, 1 to 5!",
                            "{{greeting}} {{sender}} here 💛 How'd your appointment with {{technician}} go today? "
                                    + "Reply with a number 1-5, I read every one!",
                            "{{greeting}} {{sender}} checking in 💛 {{technician}} took care of you today — how'd "
                                    + "everything turn out? Just text back 1 to 5!",
                            "Hey, {{sender}} here again 💛 Quick one — how was your visit with {{technician}} "
                                    + "today? A number from 1-5 tells me everything!",
                            "{{greeting}} It's {{sender}} 💛 Hope {{technician}} took great care of you today! "
                                    + "Mind rating it 1-5 real quick?"
                    ),
                    List.of("greeting", "sender", "technician")
            )),
            Map.entry("checkout_rating_request_no_technician", new TemplateDefault(
                    "checkout_rating_request_no_technician", "checkout_review_request", SmsMessageClass.TRANSACTIONAL,
                    "Rating request (technician unknown)",
                    List.of(
                            "{{greeting}} It's {{sender}} from {{businessName}} 💛 I like to personally check in after "
                                    + "every visit. How'd we do? Just reply with a number, 1 to 5!",
                            "{{greeting}} {{sender}} from {{businessName}} here 💛 Always love checking in after a "
                                    + "visit — how'd today go? Reply 1 to 5!",
                            "Hey! {{sender}} with {{businessName}} 💛 How was everything today? A quick number, "
                                    + "1-5, means a lot to me!",
                            "{{greeting}} It's {{sender}} 💛 Just wanted to personally follow up — how did your "
                                    + "visit with us at {{businessName}} go? 1 to 5!",
                            "{{greeting}} {{sender}} here from {{businessName}} 💛 Checking in like I always do — "
                                    + "mind rating today 1-5?"
                    ),
                    List.of("greeting", "sender", "businessName")
            )),
            Map.entry("checkout_review_positive", new TemplateDefault(
                    "checkout_review_positive", "checkout_review_request", SmsMessageClass.TRANSACTIONAL,
                    "5-star reply: ask for a Google review",
                    List.of(
                            "Yay, so happy to hear that! 🎉 Since you loved it, mind leaving a quick Google review? "
                                    + "Takes 10 seconds and really helps our small business: {{link}} -{{sender}}",
                            "Yay!! 🎉 So glad you loved it! Would you mind dropping a quick Google review for us? "
                                    + "It genuinely helps a small business like ours: {{link}} -{{sender}}",
                            "That makes my day! 🎉 If you have 10 seconds, a Google review would mean the world "
                                    + "to us: {{link}} -{{sender}}",
                            "So happy to hear that!! 🎉 Reviews from clients like you are what keep us going — "
                                    + "would you leave us one here? {{link}} -{{sender}}",
                            "Love that! 🎉 Since you're happy, could you share it with a quick Google review? "
                                    + "Takes just a moment: {{link}} -{{sender}}"
                    ),
                    List.of("link", "sender")
            )),
            Map.entry("checkout_review_positive_repeat", new TemplateDefault(
                    "checkout_review_positive_repeat", "checkout_review_request", SmsMessageClass.TRANSACTIONAL,
                    "5-star reply: already reviewed before",
                    List.of(
                            "So glad you loved it again! 💕 You've already clicked through to leave us a Google review "
                                    + "before. If there's any specific feedback this time, we'd love to hear it here: "
                                    + "{{link}} -{{businessName}}",
                            "Amazing, another 5 stars! 💕 You've already left us a Google review, so if there's "
                                    + "anything specific about today we'd love to hear it: {{link}} -{{businessName}}",
                            "So glad it was another great visit! 💕 Since you've already reviewed us, feel free to "
                                    + "share any thoughts on today here instead: {{link}} -{{businessName}}",
                            "You're the best 💕 Since you've already left us a review, if anything stood out about "
                                    + "today we'd love to know: {{link}} -{{businessName}}",
                            "Thrilled you loved it again! 💕 No need for another Google review, but if you have "
                                    + "any feedback on today specifically, share it here: {{link}} -{{businessName}}"
                    ),
                    List.of("link", "businessName")
            )),
            // No {{link}} here on purpose — a customer who just texted back a low rating is
            // already mid-conversation and typing replies works fine; routing them to a Google
            // Form instead just adds friction and loses the objection-handling opportunity a
            // direct text reply gives us (see owner feedback). checkout_review_positive_repeat
            // still uses the feedback-form link for a different case (a repeat 5-star reviewer),
            // untouched here.
            Map.entry("checkout_review_negative", new TemplateDefault(
                    "checkout_review_negative", "checkout_review_request", SmsMessageClass.TRANSACTIONAL,
                    "Low-rating reply: service recovery",
                    List.of(
                            "I'm really sorry today wasn't a 5 for you 💛 I'd love to make it right personally. "
                                    + "Just reply and let me know what happened! -{{sender}}, Manager",
                            "I'm sorry to hear today wasn't a 5 💛 I really want to make this right — can you tell "
                                    + "me what happened? -{{sender}}, Manager",
                            "That's not the experience I want for you 💛 Please reply and let me know what went "
                                    + "wrong so I can fix it personally. -{{sender}}, Manager",
                            "So sorry today didn't go as it should have 💛 I'd love to hear what happened, just "
                                    + "reply here. -{{sender}}, Manager",
                            "I hate hearing that 💛 Let me make it right — reply and tell me what happened today. "
                                    + "-{{sender}}, Manager"
                    ),
                    List.of("sender")
            )),
            Map.entry("lead_follow_up_nudge", new TemplateDefault(
                    "lead_follow_up_nudge", "lead_follow_up", SmsMessageClass.TRANSACTIONAL,
                    "Lead follow-up",
                    List.of("{{greeting}} It's {{sender}} from {{businessName}} 💛 Do you need help with more openings or "
                            + "is there anything specific you are looking for?"),
                    List.of("greeting", "sender", "businessName")
            )),
            // Plain service reminder, no discount/link — a helpful nudge, not a promo, so
            // TRANSACTIONAL (same reasoning as lead_follow_up_nudge above). Wording is a starting
            // point, not final copy — owner-editable like every other template here.
            Map.entry("touchup_reminder_nudge", new TemplateDefault(
                    "touchup_reminder_nudge", "touchup_reminder", SmsMessageClass.TRANSACTIONAL,
                    "Touch-up reminder",
                    List.of("{{greeting}} It's {{sender}} from {{businessName}} 💛 It's been about 4 weeks since your "
                            + "procedure — touch-ups done in the 4-6 week window help lock in your result best. "
                            + "Want me to grab you a spot?"),
                    List.of("greeting", "sender", "businessName")
            )),
            // spotClause is pre-computed by the caller: "want to lock in your next spot" or
            // "want to lock in your next spot with {technician}" — see class doc on why this is a
            // clause variable rather than a second key.
            Map.entry("same_day_rebooking_nudge", new TemplateDefault(
                    "same_day_rebooking_nudge", "same_day_rebooking_discount", SmsMessageClass.MARKETING,
                    "Same-day nudge (consented)",
                    List.of(
                            "Hope you're loving your nails 💛 Since you're already here today, {{spotClause}}? "
                                    + "I'll knock {{discountAmount}} off if you book before midnight: {{link}}",
                            "Hope you're loving your nails 💛 While you're already here today, {{spotClause}}? "
                                    + "Book before midnight and I'll take {{discountAmount}} off: {{link}}",
                            "Loving the new set? 💛 Since you're already here, {{spotClause}}? Grab "
                                    + "{{discountAmount}} off if you lock it in before midnight: {{link}}",
                            "Hope everything looks great! 💛 You're already here today, so {{spotClause}}? "
                                    + "{{discountAmount}} off if you book before midnight: {{link}}",
                            "Hope you're obsessed with your nails 💛 Quick thought — {{spotClause}}? Today only, "
                                    + "{{discountAmount}} off before midnight: {{link}}"
                    ),
                    List.of("spotClause", "discountAmount", "link")
            )),
            // urgencyClause is pre-computed: "Spots are filling up fast this time of year" or
            // "{technician}'s spots are filling up fast this time of year".
            Map.entry("same_day_rebooking_reminder", new TemplateDefault(
                    "same_day_rebooking_reminder", "same_day_rebooking_discount", SmsMessageClass.TRANSACTIONAL,
                    "Same-day nudge (no consent on file)",
                    List.of(
                            "{{urgencyClause}} 💛 Might be worth grabbing your next one today instead of the usual "
                                    + "3-4 week wait: {{link}}",
                            "{{urgencyClause}} 💛 Worth locking in your next spot today instead of waiting the "
                                    + "usual 3-4 weeks: {{link}}",
                            "{{urgencyClause}} 💛 Might be smart to grab your next appointment now rather than "
                                    + "wait 3-4 weeks like usual: {{link}}",
                            "{{urgencyClause}} 💛 If you book today you can skip the usual 3-4 week wait: {{link}}",
                            "{{urgencyClause}} 💛 Today's a good day to grab your next spot instead of waiting "
                                    + "the usual few weeks: {{link}}"
                    ),
                    List.of("urgencyClause", "link")
            )),
            // offerClause is pre-computed: "It's been 3+ weeks since your last visit. Spots are
            // filling up fast right now, grabbed you $5 off if you book today" or the same with
            // "and {technician}'s schedule is almost full" inserted.
            Map.entry("lapsed_customer_winback_nudge", new TemplateDefault(
                    "lapsed_customer_winback_nudge", "lapsed_customer_winback", SmsMessageClass.MARKETING,
                    "Lapsed win-back (consented)",
                    List.of("{{greeting}} It's {{sender}} from {{businessName}} 💛 {{offerClause}}: {{link}} -{{sender}}"),
                    List.of("greeting", "sender", "offerClause", "link", "businessName")
            )),
            // offerClause here: "It's been 3+ weeks since your last visit. Spots are filling up
            // fast right now, want me to grab you a spot" (or with technician's schedule inserted).
            Map.entry("lapsed_customer_winback_reminder", new TemplateDefault(
                    "lapsed_customer_winback_reminder", "lapsed_customer_winback", SmsMessageClass.TRANSACTIONAL,
                    "Lapsed win-back (no consent on file)",
                    List.of("{{greeting}} It's {{sender}} from {{businessName}} 💛 {{offerClause}}? {{link}} -{{sender}}"),
                    List.of("greeting", "sender", "offerClause", "link", "businessName")
            )),
            // visitClause is pre-computed: "It's been a while since your last visit" or "...with
            // {technician}".
            Map.entry("repeat_customer_winback_nudge_default", new TemplateDefault(
                    "repeat_customer_winback_nudge_default", "repeat_customer_winback", SmsMessageClass.MARKETING,
                    "Repeat win-back, same technician (consented)",
                    List.of("{{greeting}} It's {{sender}} from {{businessName}} 💛 {{visitClause}}. Grabbed you "
                            + "{{discountAmount}} off if you book today: {{link}} -{{sender}}"),
                    List.of("greeting", "sender", "visitClause", "discountAmount", "link", "businessName")
            )),
            Map.entry("repeat_customer_winback_reminder_default", new TemplateDefault(
                    "repeat_customer_winback_reminder_default", "repeat_customer_winback", SmsMessageClass.TRANSACTIONAL,
                    "Repeat win-back, same technician (no consent on file)",
                    List.of("{{greeting}} It's {{sender}} from {{businessName}} 💛 {{visitClause}}. Book your next mani "
                            + "here: {{link}} -{{sender}}"),
                    List.of("greeting", "sender", "visitClause", "link", "businessName")
            )),
            Map.entry("repeat_customer_winback_nudge_previous_provider", new TemplateDefault(
                    "repeat_customer_winback_nudge_previous_provider", "repeat_customer_winback", SmsMessageClass.MARKETING,
                    "Repeat win-back, technician changed (consented)",
                    List.of("{{greeting}} It's {{sender}} from {{businessName}} 💛 It's been a while since we've seen "
                            + "you, want me to check if {{previousProvider}} has an opening for you? Grabbed "
                            + "you {{discountAmount}} off if you book today: {{link}} -{{sender}}"),
                    List.of("greeting", "sender", "previousProvider", "discountAmount", "link", "businessName")
            )),
            Map.entry("repeat_customer_winback_reminder_previous_provider", new TemplateDefault(
                    "repeat_customer_winback_reminder_previous_provider", "repeat_customer_winback", SmsMessageClass.TRANSACTIONAL,
                    "Repeat win-back, technician changed (no consent on file)",
                    List.of("{{greeting}} It's {{sender}} from {{businessName}} 💛 It's been a while since we've seen "
                            + "you, want me to check if {{previousProvider}} has an opening for you? Book "
                            + "here: {{link}} -{{sender}}"),
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
