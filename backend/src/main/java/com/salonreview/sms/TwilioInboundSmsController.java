package com.salonreview.sms;

import com.salonreview.config.TwilioInboundProperties;
import com.salonreview.domain.BlockedNumber;
import com.salonreview.domain.SmsMessage;
import com.salonreview.domain.SmsReplyFlow;
import com.salonreview.domain.TwilioSmsConfig;
import com.salonreview.marketing.MarketingContactsService;
import com.salonreview.repo.BlockedNumberRepository;
import com.salonreview.repo.BusinessRepository;
import com.salonreview.repo.SmsReplyFlowRepository;
import com.salonreview.repo.TwilioSmsConfigRepository;
import com.salonreview.telegram.TelegramNotificationService;
import com.salonreview.util.PhoneNumbers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Receives Twilio's inbound-SMS webhook and branches the checkout-review-request automation's
 * reply — see openspec/changes/sms-automations-hub design.md D4. {@code permitAll()} in
 * {@link com.salonreview.config.SecurityConfig}; auth is the HMAC signature check below, not a
 * session (Twilio has none).
 */
@RestController
public class TwilioInboundSmsController {

    private static final Logger log = LoggerFactory.getLogger(TwilioInboundSmsController.class);

    /** The standard CTIA/Twilio opt-out keyword set (see Twilio's Advanced Opt-Out docs) — an
     * exact, whole-body match (case-insensitive, trimmed) only, never a substring match, so a
     * message that merely mentions "stop" mid-sentence ("please stop calling me at night") is
     * never mistaken for a legal opt-out request. This account's Twilio number does not have
     * Advanced Opt-Out intercepting these before they reach this webhook, so without this check
     * they were logged as ordinary free-text replies with no marking and no effect on future
     * sends — exactly the gap that was reported. */
    private static final Set<String> OPT_OUT_KEYWORDS =
            Set.of("STOP", "STOPALL", "UNSUBSCRIBE", "CANCEL", "END", "QUIT");

    private final TwilioInboundProperties properties;
    private final SmsMessageLogService messageLogService;
    private final SmsReplyFlowRepository replyFlowRepository;
    private final CheckoutReviewReplyService replyService;
    private final TelegramNotificationService telegramService;
    private final MarketingContactsService contactsService;
    private final BlockedNumberRepository blockedNumberRepository;
    private final SmsMediaService mediaService;
    private final SmsReactionService reactionService;
    private final BusinessRepository businesses;
    private final TwilioSmsConfigRepository twilioConfigs;

    public TwilioInboundSmsController(TwilioInboundProperties properties, SmsMessageLogService messageLogService,
                                       SmsReplyFlowRepository replyFlowRepository, CheckoutReviewReplyService replyService,
                                       TelegramNotificationService telegramService, MarketingContactsService contactsService,
                                       BlockedNumberRepository blockedNumberRepository, SmsMediaService mediaService,
                                       SmsReactionService reactionService, BusinessRepository businesses,
                                       TwilioSmsConfigRepository twilioConfigs) {
        this.properties = properties;
        this.messageLogService = messageLogService;
        this.replyFlowRepository = replyFlowRepository;
        this.replyService = replyService;
        this.telegramService = telegramService;
        this.contactsService = contactsService;
        this.blockedNumberRepository = blockedNumberRepository;
        this.mediaService = mediaService;
        this.reactionService = reactionService;
        this.businesses = businesses;
        this.twilioConfigs = twilioConfigs;
    }

    @PostMapping(value = "/api/public/sms/inbound", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> receive(@RequestHeader(value = "X-Twilio-Signature", required = false) String signature,
                                         @RequestParam Map<String, String> params) {
        if (!properties.isConfigured() || !TwilioSignature.valid(properties.getAuthToken(), properties.getWebhookUrl(), params, signature)) {
            log.warn("Twilio inbound SMS rejected — missing/invalid signature");
            return ResponseEntity.status(401).build();
        }

        // Webhooks are unauthenticated (no session), but Twilio's own "To" field (the salon's
        // number the customer texted) is real business signal now that twilio_sms_config is
        // business-scoped (V95/V103) — resolve the real business from it instead of hardcoding.
        // Falls back to legacySmsBusiness() only for an unrecognized destination number, which
        // shouldn't happen in practice but must not 500/drop the message if it ever does.
        String to = params.get("To");
        Long businessId = (to == null ? Optional.<TwilioSmsConfig>empty() : twilioConfigs.findByFromPhoneNumber(to))
                .map(TwilioSmsConfig::getBusinessId)
                .orElseGet(() -> {
                    log.warn("Twilio inbound SMS to unrecognized number \"{}\" — falling back to legacySmsBusiness()", to);
                    return businesses.legacySmsBusiness().getId();
                });
        String from = params.get("From");
        String body = params.getOrDefault("Body", "");
        if (from == null || from.isBlank()) {
            return ResponseEntity.ok().build();
        }

        boolean isOptOut = OPT_OUT_KEYWORDS.contains(body.trim().toUpperCase(Locale.US));

        // Log unconditionally — even a reply that matches no pending flow (or is itself an
        // opt-out) needs to be visible in the hub's inbox (see design.md D9).
        Optional<SmsReplyFlow> pending = replyFlowRepository
                .findFirstByBusinessIdAndPhoneNumberAndStateOrderByCreatedAtDesc(businessId, from, SmsReplyFlow.STATE_AWAITING_REPLY);
        // Only checkout_review_request opens a durable SmsReplyFlow; every other automation
        // (lead_follow_up, same_day_rebooking_discount, lapsed_customer_winback,
        // repeat_customer_winback, ...) falls back to "whatever we most recently texted this
        // number" so a genuine reply still shows up as a tracked reply for that automation instead
        // of being silently unattributed — see SmsMessageLogService#mostRecentAutomationKey.
        String automationKey = pending.map(SmsReplyFlow::getAutomationKey)
                .orElseGet(() -> messageLogService.mostRecentAutomationKey(businessId, from));
        SmsMessage logged = messageLogService.logInbound(businessId, from, body, automationKey);
        logged.setTwilioMessageSid(params.get("MessageSid"));
        messageLogService.save(logged);

        // A STOP-style reply is a legally binding opt-out (TCPA/CTIA) — block the number right
        // away so every later step in this handler (and every future automation/manual send, via
        // TwilioSmsService's single choke point) already sees it as blocked. Insert-if-absent
        // rather than always re-saving (unlike the manual "Block number" action's own doc comment)
        // so a customer who already blocked themselves, then texts STOP again, doesn't churn
        // blocked_at or downgrade an existing manual block's source.
        String normalizedFrom = PhoneNumbers.normalize(from);
        if (isOptOut && !blockedNumberRepository.existsById(normalizedFrom)) {
            blockedNumberRepository.save(BlockedNumber.builder()
                    .phoneNumber(normalizedFrom).source(BlockedNumber.SOURCE_STOP_REQUEST).build());
            log.info("Number blocked — replied with opt-out keyword");
        }

        // MMS photos, if any — see SmsMediaService's own doc for why this is best-effort and
        // never blocks the rest of this handler (the text/thread above is already durable).
        mediaService.ingestInboundMedia(logged.getId(), params);

        // An Apple tapback-over-SMS reaction (e.g. `Loved "..."`) still lands here as an ordinary
        // text — it's logged above like any other inbound message either way — but if it parses and
        // matches one of the salon's recent sends, it's also attached as a reaction on that message
        // (see design context: owner wants to see when a customer reacts to a specific message).
        // Best-effort, same reasoning as the media ingestion above.
        reactionService.tryAttachCustomerReaction(businessId, from, body);

        // A customer reply always needs a human's attention right away, not just a dashboard entry
        // nobody's actively watching — see openspec/changes/sms-automations-hub proposal.md. Name
        // resolution is best-effort (same ladder resolveDisplayNames already uses for the Messages
        // page itself) — a phone number with nothing resolvable still gets an alert, just without
        // a name in the header. Skipped entirely for a number a manager has blocked (see V61) —
        // the message itself is still logged above (so it's visible if anyone opens that thread),
        // just without pinging Telegram for a number already decided not worth engaging with.
        if (!blockedNumberRepository.existsById(normalizedFrom)) {
            String customerName = resolveCustomerName(from);
            telegramService.sendInboundSmsAlert(from, customerName, body, logged.getAutomationKey());
        }

        // A STOP-style reply isn't a satisfaction-rating reply — the block above already stops
        // any branch reply this would otherwise trigger from ever actually sending, but skip the
        // branching/negative-feedback logic entirely rather than relying on that as a backstop.
        if (isOptOut) {
            // no-op, per the comment above
        } else if (pending.isPresent()) {
            SmsReplyFlow flow = pending.get();
            boolean positive = body.contains("5"); // digits only — "Five" spelled out doesn't match, see design.md D4
            if (containsLowRatingDigit(body)) {
                logged.setNegativeFeedbackAt(Instant.now());
                messageLogService.save(logged);
            }
            try {
                replyService.sendBranchReply(flow, positive);
                flow.setState(SmsReplyFlow.STATE_COMPLETED);
                replyFlowRepository.save(flow);
            } catch (RuntimeException e) {
                // 2026-08-16 live incident: 3 real "5" replies left their flow stuck in
                // AWAITING_REPLY with no branch reply ever sent and zero application-level log line
                // naming why — the previous version of this method let any exception here propagate
                // silently (as a bare 500 to Twilio). This is that log line. Recoverable via
                // POST /api/owner/settings/sms/reply-flows/{id}/retry once the cause is fixed.
                log.error("Checkout-review branch reply failed for flow {} ({}, positive={}) — flow "
                        + "left AWAITING_REPLY, recoverable via reply-flows/{}/retry",
                        flow.getId(), from, positive, flow.getId(), e);
                throw e;
            }
        } else if (CheckoutReviewReplyService.AUTOMATION_KEY.equals(automationKey)) {
            // Same incident, the other half of "stuck with no evidence": a reply that looks exactly
            // like a checkout-review rating (it's the customer's most recent automation) but no
            // AWAITING_REPLY row exists for their number at all — this is the case actually observed
            // in the 2026-08-16 incident (flow rows were confirmed correctly AWAITING_REPLY minutes
            // to half an hour before the reply arrived, ruling out a timing race, yet this branch
            // was apparently still what ran). Previously silent; now visible.
            log.warn("Inbound SMS from {} (body=\"{}\") looks like a checkout-review reply but no "
                    + "AWAITING_REPLY flow was found for this number — the rating was never followed "
                    + "up on. Check sms_reply_flow for a row that should be AWAITING_REPLY but isn't.",
                    from, body);
        }
        return ResponseEntity.ok().build();
    }

    /** Best-effort given+family name for the Telegram alert header — null (not "—") when nothing
     * resolves, so the alert falls back to showing just the phone number instead of an empty
     * name. */
    private String resolveCustomerName(String phoneNumber) {
        MarketingContactsService.ContactNameInfo info = contactsService.resolveDisplayNames(java.util.List.of(phoneNumber)).get(phoneNumber);
        if (info == null || info.givenName() == null || info.givenName().isBlank()) return null;
        return info.familyName() == null || info.familyName().isBlank()
                ? info.givenName()
                : info.givenName() + " " + info.familyName();
    }

    /** A reply containing any of 1-4 — a low star rating — permanently excludes this customer
     * from the same-day-rebooking win-back nudge (see {@code SameDayRebookingScheduler}); see
     * negative-feedback-tracking design. Digits only, same convention as the positive check
     * above — no attempt to parse spelled-out numbers. */
    private static boolean containsLowRatingDigit(String body) {
        return body.chars().anyMatch(c -> c >= '1' && c <= '4');
    }
}
