package com.salonreview.sms;

import com.salonreview.domain.Business;
import com.salonreview.domain.SmsMessage;
import com.salonreview.domain.SmsReplyFlow;
import com.salonreview.domain.TwilioSmsConfig;
import com.salonreview.repo.BusinessRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Sends the checkout-review-request automation's branch replies (positive: Google review link,
 * or feedback-form link for a repeat reviewer; negative: a plain ask to reply with what
 * happened, no link) — see openspec/changes/sms-automations-hub design.md D4/D6.
 *
 * <p>Deliberately bypasses {@link TwilioSmsService#sendTemplated} and
 * {@link SmsTemplateRegistry}: the two positive-branch messages must contain a self-referencing
 * short link (an opaque {@link ClickTokens}-generated token, not the row's own id — see design.md
 * D6), generated up front and reserved on a placeholder row before the real body is rendered and
 * the row updated once the send outcome is known. The negative branch carries no link (see
 * {@code checkout_review_negative}'s catalog doc), so it skips the click-token reservation
 * entirely. Every branch's template is TRANSACTIONAL (see design.md D5) and sent unconditionally
 * once a flow reaches {@code AWAITING_REPLY} — disabling the automation only stops *new* flows
 * from being enqueued, it doesn't leave an already-replying customer hanging (see tasks.md 10.4).
 */
@Service
public class CheckoutReviewReplyService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutReviewReplyService.class);
    static final String AUTOMATION_KEY = "checkout_review_request";

    /** A reply that goes out the instant a customer's rating digit arrives reads as an obvious
     * bot, not a person on the other end — this small, deliberate pause is enough to break that
     * impression without the customer noticing an actual wait (see owner feedback). */
    static final Duration REPLY_DELAY = Duration.ofSeconds(3);

    private final SmsMessageLogService messageLogService;
    private final TwilioSmsConfigService configService;
    private final TwilioSmsClient client;
    private final SmsMessageTemplateService templateService;
    private final String publicBaseUrl;
    private final TaskScheduler taskScheduler;
    private final BusinessRepository businessRepository;

    public CheckoutReviewReplyService(SmsMessageLogService messageLogService, TwilioSmsConfigService configService,
                                       TwilioSmsClient client, SmsMessageTemplateService templateService,
                                       @Value("${app.public-base-url}") String publicBaseUrl,
                                       TaskScheduler taskScheduler, BusinessRepository businessRepository) {
        this.messageLogService = messageLogService;
        this.configService = configService;
        this.client = client;
        this.templateService = templateService;
        this.publicBaseUrl = publicBaseUrl;
        this.taskScheduler = taskScheduler;
        this.businessRepository = businessRepository;
    }

    /** Reserves the row and renders the body immediately (cheap, no external calls), but delays
     * the actual Twilio send by {@link #REPLY_DELAY} — see that constant's own doc for why. */
    public void sendBranchReply(SmsReplyFlow flow, boolean positive) {
        // A proven repeat 5-star reviewer (already clicked through to Google before) doesn't need
        // to be asked for another public review every single time — that reads as spammy to them
        // and to Google's own review-quality checks. Route them to the private feedback form
        // instead, same destination the negative branch used to use, just with warmer copy.
        boolean repeatReviewer = positive
                && messageLogService.hasClickedLinkTarget(flow.getBusinessId(), flow.getPhoneNumber(), CheckoutReviewLinks.GOOGLE_REVIEW_TARGET);

        String templateKey = positive
                ? (repeatReviewer ? "checkout_review_positive_repeat" : "checkout_review_positive")
                : "checkout_review_negative";

        String sender = configService.get(flow.getBusinessId()).getSenderName();
        Business business = businessRepository.findById(flow.getBusinessId()).orElse(null);
        String businessName = business == null ? "" : business.getName();

        SmsMessage reserved;
        String body;
        if (positive) {
            // Google review / feedback-form link — see class doc on why these two branches still
            // need a self-referencing click-tracked short link and the negative branch below doesn't.
            String linkTarget = repeatReviewer ? CheckoutReviewLinks.FEEDBACK_FORM_TARGET : CheckoutReviewLinks.GOOGLE_REVIEW_TARGET;
            String clickToken = messageLogService.generateUniqueClickToken();
            reserved = messageLogService.logOutboundWithLink(
                    flow.getBusinessId(), templateKey, AUTOMATION_KEY, flow.getPhoneNumber(),
                    "", false, "pending", null, linkTarget, clickToken);
            String shortLink = publicBaseUrl + "/r/" + clickToken;
            body = templateService.render(flow.getBusinessId(), templateKey,
                    java.util.Map.of("link", shortLink, "sender", sender, "businessName", businessName));
        } else {
            // No link — a low rating gets a plain ask to reply and say what happened, handled
            // directly in the conversation rather than routed to a Google Form (see
            // checkout_review_negative's own catalog doc for why). No click-token reservation
            // needed since there's no short link for this branch to carry.
            reserved = messageLogService.logOutbound(
                    flow.getBusinessId(), templateKey, AUTOMATION_KEY, flow.getPhoneNumber(), "", false, "pending", null);
            body = templateService.render(flow.getBusinessId(), templateKey,
                    java.util.Map.of("sender", sender, "businessName", businessName));
        }

        taskScheduler.schedule(() -> sendNow(flow, reserved, body), Instant.now().plus(REPLY_DELAY));
    }

    private void sendNow(SmsReplyFlow flow, SmsMessage reserved, String body) {
        TwilioSmsConfig config = configService.get(flow.getBusinessId());
        if (!config.isConfigured()) {
            log.info("Checkout-review branch reply skipped — Twilio credentials not configured");
            updateReserved(reserved, body, false, "not_configured", null);
            return;
        }
        try {
            String twilioMessageSid = client.send(config, flow.getPhoneNumber(), body);
            updateReserved(reserved, body, true, null, twilioMessageSid);
        } catch (Exception e) {
            log.warn("Checkout-review branch reply send failed (caller unaffected): {}", e.getMessage());
            updateReserved(reserved, body, false, "send_failed", null);
        }
    }

    private void updateReserved(SmsMessage reserved, String body, boolean sent, String reason, String twilioMessageSid) {
        reserved.setBody(body);
        reserved.setStatus(sent ? "SENT" : "NOT_SENT");
        reserved.setReason(reason);
        reserved.setTwilioMessageSid(twilioMessageSid);
        messageLogService.save(reserved);
    }
}
