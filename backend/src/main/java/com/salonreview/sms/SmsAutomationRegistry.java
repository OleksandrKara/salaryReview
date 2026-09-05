package com.salonreview.sms;

import java.util.List;
import java.util.Map;

/**
 * Static, code-level metadata (name + plain-English audience description) per automation key —
 * matches this codebase's "no CMS yet" convention (see {@link SmsTemplateRegistry}). The
 * enabled/disabled *state* itself is DB-backed ({@code sms_automation}, see
 * {@link com.salonreview.domain.SmsAutomation}) so the owner can toggle it; this class only
 * describes what each key means.
 */
public final class SmsAutomationRegistry {

    /** {@code primaryTemplateKeys} disambiguates "how many times did this automation actually
     * fire" from the automation's total outbound row count, for the one automation
     * ({@code checkout_review_request}) that logs more than one distinct template under the same
     * {@code automationKey} — its conditional branch reply (Google review / feedback form) shares
     * the key but isn't itself a new firing of the automation. Two entries here (not one) since
     * the rating request itself picks between a with-technician / no-technician template variant
     * (see {@code SmsMessageTemplateCatalog}) — either counts as one firing. Empty for every other
     * automation, which only ever logs one template per key, so no filtering is needed.
     *
     * <p>{@code SmsAutomationService#list()} also reuses "non-empty" here as the signal for
     * whether the reply count needs to be flow-scoped rather than a plain inbound-message count —
     * the same "more than one template/interaction under one automationKey" property that makes
     * the sent count need filtering is what lets a reply thread balloon past the actual number of
     * rating requests sent (see that query's own doc).
     *
     * <p>{@code tracksClicks}/{@code tracksReplies} say whether a click-through rate / reply rate
     * is even a meaningful thing to show for this automation — see
     * {@code SmsAutomationService#list()}, which only queries those counts when true rather than
     * showing a misleading "0%" for an automation that never links or asks for a reply at all.
     *
     * <p>{@code tracksConversion} says whether this automation's actual business outcome — did the
     * customer come back for a real visit afterward, not just click or reply — can be measured from
     * data we already have: every automation whose whole point is a future visit (both winbacks,
     * both service-lifecycle reminders, same-day rebooking) sets this, since {@code provider_visit}
     * lets us check directly whether a completed visit happened after the send. Unset for
     * automations whose "outcome" isn't a future visit at all — {@code checkout_review_request}'s
     * is a rating, {@code lead_follow_up}/{@code consultation_lead_sms}/{@code four_hand_request}
     * are single confirmations with nothing further to convert into.
     */
    public record AutomationMeta(String key, String name, String audienceDescription,
                                  List<String> primaryTemplateKeys, boolean tracksClicks, boolean tracksReplies,
                                  boolean tracksConversion) {}

    private static final Map<String, AutomationMeta> META = Map.of(
            "four_hand_request", new AutomationMeta(
                    "four_hand_request",
                    "4-Hand request confirmation",
                    "Every customer who submits a 4-Hand manicure/pedicure request on mani or akluxnails-home",
                    List.of(), false, false, false
            ),
            "consultation_lead_sms", new AutomationMeta(
                    "consultation_lead_sms",
                    "Consultation booking confirmation",
                    "Every customer who books a PMU consultation — Square's own confirmation text doesn't "
                            + "reliably fire for this booking type, so a custom one is sent instead",
                    List.of(), false, false, false
            ),
            "checkout_review_request", new AutomationMeta(
                    "checkout_review_request",
                    "Post-checkout satisfaction request",
                    "Every customer who completes an in-salon checkout at the register — 2 minutes later, "
                            + "asked to rate their visit 1–5. A 5-star reply is routed to a Google review the "
                            + "first time, a Yelp review once Google's already been clicked, then a private "
                            + "feedback form once both have been; a low rating gets a plain reply asking what "
                            + "happened. A customer who never replies by text at all gets a one-tap emoji-rating "
                            + "email 24 hours later (see CheckoutReviewEmailFallbackScheduler) — never a second "
                            + "channel for someone who already answered.",
                    // "checkout_rating_request" (no suffix) is the pre-2026-08-20 template key,
                    // from before the with-technician/no-technician split — still real, recent
                    // history inside any 30-day window until 2026-09-20, after which no row will
                    // carry it anymore and this entry becomes safe to delete. Found live
                    // 2026-08-21: omitting it undercounted "sent" by 117 messages for business 1
                    // alone, which is what actually produced the 857% ("60/7") reply-rate bug —
                    // not just the reply-count overcounting fixed alongside this.
                    List.of("checkout_rating_request", "checkout_rating_request_with_technician",
                            "checkout_rating_request_no_technician"),
                    true, true, false
            ),
            "lead_follow_up", new AutomationMeta(
                    "lead_follow_up",
                    "Lead follow-up nudge",
                    "Every lead who leaves contact info but has no upcoming appointment 2 minutes later — "
                            + "a purely helpful, no-incentive text offering to help find a time. Still unbooked "
                            + "at ~24h gets an email properly introducing the studio; still unbooked at ~72h "
                            + "gets one final plain check-in text (see LeadFollowUpScheduler).",
                    // Restricted to the step 1 template only — lead_follow_up_final_nudge (step 3)
                    // shares this same automationKey, and an unfiltered "any send under this key"
                    // count would conflate a NEW lead's first nudge with an existing one's final
                    // check-in, the exact 857% overcounting bug checkout_review_request's own
                    // primaryTemplateKeys entry already fixed for the same reason.
                    List.of("lead_follow_up_nudge"), false, false, false
            ),
            "same_day_rebooking_discount", new AutomationMeta(
                    "same_day_rebooking_discount",
                    "Same-day rebooking discount",
                    "Every in-salon checkout, 3 hours later, if they haven't already rebooked and have "
                            + "given SMS-marketing consent (in this app or in Square) — a $10-off nudge to "
                            + "rebook before midnight, min. $99 order. Customers who neither click nor reply "
                            + "by evening also get a follow-up email — see WinbackEmailFallbackScheduler.",
                    List.of(), true, true, true
            ),
            "lapsed_customer_winback", new AutomationMeta(
                    "lapsed_customer_winback",
                    "Lapsed customer win-back",
                    "Every customer with exactly one all-time visit, 21–35 days after that visit, if they "
                            + "haven't already rebooked — a one-time nudge naming their technician's own "
                            + "schedule; consented customers see a $5-off coupon (min. $99 order) valid until "
                            + "midnight that day, everyone else gets the same link with no discount language. "
                            + "Customers who neither click nor reply by evening also get a follow-up email — "
                            + "see WinbackEmailFallbackScheduler.",
                    List.of(), true, true, true
            ),
            "touchup_reminder", new AutomationMeta(
                    "touchup_reminder",
                    "Touch-up reminder",
                    "Every customer roughly 4 weeks after a service configured as an \"initial procedure\" "
                            + "(see Service lifecycle settings) — skipped if they've already had or booked a "
                            + "service configured as the matching \"touch-up\". Inert until both roles have at "
                            + "least one service configured for this business.",
                    // tracksConversion, like repeat_customer_winback: did the customer actually
                    // come back for a real visit, not just receive the text — see
                    // ServiceLifecycleReminderSendRepository#countConvertedSince's own doc for why
                    // this checks "any subsequent visit," not specifically a touch-up.
                    List.of(), false, false, true
            ),
            "color_booster_reminder", new AutomationMeta(
                    "color_booster_reminder",
                    "Annual color booster reminder",
                    "Every customer roughly 12+ months past their most recent \"initial procedure\" or "
                            + "\"color booster\" (see Service lifecycle settings) — skipped if they've already "
                            + "booked a color booster. Recurs roughly annually for a customer who never books. "
                            + "Inert until both roles have at least one service configured for this business.",
                    List.of(), false, false, true
            ),
            "repeat_customer_winback", new AutomationMeta(
                    "repeat_customer_winback",
                    "Repeat customer win-back",
                    "Every customer with 2+ all-time visits, 40+ days after their last visit, if they haven't "
                            + "already rebooked — a plain, no-discount check-in with a booking link; if their "
                            + "technician changed at their last visit, the text offers to check with their "
                            + "earlier technician by name instead. Repeats every time a customer re-lapses, "
                            + "subject to a 60-day cooldown per customer. Customers who neither click nor reply "
                            + "by evening also get a follow-up email — see WinbackEmailFallbackScheduler.",
                    List.of(), true, true, true
            ),
            // Email-only (owner request 2026-09-05) — no SMS leg at all, so sentLast30Days always
            // reads 0 here (a real, accurate count — zero texts sent under this key — just not
            // where the meaningful "email sent" number lives; see PreVisitNurtureScheduler, which
            // logs its own state on pre_visit_nurture_send rather than sms_message/winback_email_send).
            "pre_visit_nurture", new AutomationMeta(
                    "pre_visit_nurture",
                    "Pre-visit nurture emails",
                    "Every customer with a confirmed booking — a warm welcome email shortly after "
                            + "booking, then (if the appointment is far enough out) a day-before reminder. "
                            + "Goal is fewer cancellations/no-shows through familiarity with the studio before "
                            + "the visit, not a booking-conversion ask.",
                    List.of(), false, false, false
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
