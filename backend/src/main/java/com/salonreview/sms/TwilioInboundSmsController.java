package com.salonreview.sms;

import com.salonreview.config.TwilioInboundProperties;
import com.salonreview.domain.SmsMessage;
import com.salonreview.domain.SmsReplyFlow;
import com.salonreview.marketing.MarketingContactsService;
import com.salonreview.repo.BlockedNumberRepository;
import com.salonreview.repo.SmsReplyFlowRepository;
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
import java.util.Map;
import java.util.Optional;

/**
 * Receives Twilio's inbound-SMS webhook and branches the checkout-review-request automation's
 * reply — see openspec/changes/sms-automations-hub design.md D4. {@code permitAll()} in
 * {@link com.salonreview.config.SecurityConfig}; auth is the HMAC signature check below, not a
 * session (Twilio has none).
 */
@RestController
public class TwilioInboundSmsController {

    private static final Logger log = LoggerFactory.getLogger(TwilioInboundSmsController.class);

    private final TwilioInboundProperties properties;
    private final SmsMessageLogService messageLogService;
    private final SmsReplyFlowRepository replyFlowRepository;
    private final CheckoutReviewReplyService replyService;
    private final TelegramNotificationService telegramService;
    private final MarketingContactsService contactsService;
    private final BlockedNumberRepository blockedNumberRepository;
    private final SmsMediaService mediaService;
    private final SmsReactionService reactionService;

    public TwilioInboundSmsController(TwilioInboundProperties properties, SmsMessageLogService messageLogService,
                                       SmsReplyFlowRepository replyFlowRepository, CheckoutReviewReplyService replyService,
                                       TelegramNotificationService telegramService, MarketingContactsService contactsService,
                                       BlockedNumberRepository blockedNumberRepository, SmsMediaService mediaService,
                                       SmsReactionService reactionService) {
        this.properties = properties;
        this.messageLogService = messageLogService;
        this.replyFlowRepository = replyFlowRepository;
        this.replyService = replyService;
        this.telegramService = telegramService;
        this.contactsService = contactsService;
        this.blockedNumberRepository = blockedNumberRepository;
        this.mediaService = mediaService;
        this.reactionService = reactionService;
    }

    @PostMapping(value = "/api/public/sms/inbound", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> receive(@RequestHeader(value = "X-Twilio-Signature", required = false) String signature,
                                         @RequestParam Map<String, String> params) {
        if (!properties.isConfigured() || !TwilioSignature.valid(properties.getAuthToken(), properties.getWebhookUrl(), params, signature)) {
            log.warn("Twilio inbound SMS rejected — missing/invalid signature");
            return ResponseEntity.status(401).build();
        }

        String from = params.get("From");
        String body = params.getOrDefault("Body", "");
        if (from == null || from.isBlank()) {
            return ResponseEntity.ok().build();
        }

        // Log unconditionally — even a reply that matches no pending flow needs to be visible in
        // the hub's inbox (see design.md D9). Twilio itself already intercepts STOP/HELP/START
        // before this endpoint ever sees them, so this only ever handles genuine free-text replies.
        Optional<SmsReplyFlow> pending = replyFlowRepository
                .findFirstByPhoneNumberAndStateOrderByCreatedAtDesc(from, SmsReplyFlow.STATE_AWAITING_REPLY);
        // Only checkout_review_request opens a durable SmsReplyFlow; every other automation
        // (lead_follow_up, same_day_rebooking_discount, lapsed_customer_winback,
        // repeat_customer_winback, ...) falls back to "whatever we most recently texted this
        // number" so a genuine reply still shows up as a tracked reply for that automation instead
        // of being silently unattributed — see SmsMessageLogService#mostRecentAutomationKey.
        String automationKey = pending.map(SmsReplyFlow::getAutomationKey)
                .orElseGet(() -> messageLogService.mostRecentAutomationKey(from));
        SmsMessage logged = messageLogService.logInbound(from, body, automationKey);
        logged.setTwilioMessageSid(params.get("MessageSid"));
        messageLogService.save(logged);

        // MMS photos, if any — see SmsMediaService's own doc for why this is best-effort and
        // never blocks the rest of this handler (the text/thread above is already durable).
        mediaService.ingestInboundMedia(logged.getId(), params);

        // An Apple tapback-over-SMS reaction (e.g. `Loved "..."`) still lands here as an ordinary
        // text — it's logged above like any other inbound message either way — but if it parses and
        // matches one of the salon's recent sends, it's also attached as a reaction on that message
        // (see design context: owner wants to see when a customer reacts to a specific message).
        // Best-effort, same reasoning as the media ingestion above.
        reactionService.tryAttachCustomerReaction(from, body);

        // A customer reply always needs a human's attention right away, not just a dashboard entry
        // nobody's actively watching — see openspec/changes/sms-automations-hub proposal.md. Name
        // resolution is best-effort (same ladder resolveDisplayNames already uses for the Messages
        // page itself) — a phone number with nothing resolvable still gets an alert, just without
        // a name in the header. Skipped entirely for a number a manager has blocked (see V61) —
        // the message itself is still logged above (so it's visible if anyone opens that thread),
        // just without pinging Telegram for a number already decided not worth engaging with.
        if (!blockedNumberRepository.existsById(PhoneNumbers.normalize(from))) {
            String customerName = resolveCustomerName(from);
            telegramService.sendInboundSmsAlert(from, customerName, body, logged.getAutomationKey());
        }

        if (pending.isPresent()) {
            SmsReplyFlow flow = pending.get();
            boolean positive = body.contains("5"); // digits only — "Five" spelled out doesn't match, see design.md D4
            if (containsLowRatingDigit(body)) {
                logged.setNegativeFeedbackAt(Instant.now());
                messageLogService.save(logged);
            }
            replyService.sendBranchReply(flow, positive);
            flow.setState(SmsReplyFlow.STATE_COMPLETED);
            replyFlowRepository.save(flow);
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
