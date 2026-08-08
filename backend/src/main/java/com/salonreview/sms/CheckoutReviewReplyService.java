package com.salonreview.sms;

import com.salonreview.domain.SmsMessage;
import com.salonreview.domain.SmsReplyFlow;
import com.salonreview.domain.TwilioSmsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Sends the checkout-review-request automation's two branch replies (Google review link /
 * feedback-form link) — see openspec/changes/sms-automations-hub design.md D4/D6.
 *
 * <p>Deliberately bypasses {@link TwilioSmsService#sendTemplated} and
 * {@link SmsTemplateRegistry}: each message's body must contain a self-referencing short link
 * (an opaque {@link ClickTokens}-generated token, not the row's own id — see design.md D6), which
 * is generated up front and reserved on a placeholder row, then the real body is rendered and the
 * row updated once the send outcome is known. Both branches are TRANSACTIONAL (see design.md D5)
 * and sent unconditionally once a flow reaches {@code AWAITING_REPLY} — disabling the automation
 * only stops *new* flows from being enqueued, it doesn't leave an already-replying customer
 * hanging (see tasks.md 10.4).
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
    private final String publicBaseUrl;
    private final TaskScheduler taskScheduler;

    public CheckoutReviewReplyService(SmsMessageLogService messageLogService, TwilioSmsConfigService configService,
                                       TwilioSmsClient client, @Value("${app.public-base-url}") String publicBaseUrl,
                                       TaskScheduler taskScheduler) {
        this.messageLogService = messageLogService;
        this.configService = configService;
        this.client = client;
        this.publicBaseUrl = publicBaseUrl;
        this.taskScheduler = taskScheduler;
    }

    /** Reserves the row and renders the body immediately (cheap, no external calls), but delays
     * the actual Twilio send by {@link #REPLY_DELAY} — see that constant's own doc for why. */
    public void sendBranchReply(SmsReplyFlow flow, boolean positive) {
        // A proven repeat 5-star reviewer (already clicked through to Google before) doesn't need
        // to be asked for another public review every single time — that reads as spammy to them
        // and to Google's own review-quality checks. Route them to the private feedback form
        // instead, same destination the negative branch already uses, just with warmer copy.
        boolean repeatReviewer = positive
                && messageLogService.hasClickedLinkTarget(flow.getPhoneNumber(), CheckoutReviewLinks.GOOGLE_REVIEW_TARGET);

        String templateKey = positive
                ? (repeatReviewer ? "checkout_review_positive_repeat" : "checkout_review_positive")
                : "checkout_review_negative";
        String linkTarget = (positive && !repeatReviewer)
                ? CheckoutReviewLinks.GOOGLE_REVIEW_TARGET
                : CheckoutReviewLinks.FEEDBACK_FORM_TARGET;

        String clickToken = messageLogService.generateUniqueClickToken();
        SmsMessage reserved = messageLogService.logOutboundWithLink(
                templateKey, AUTOMATION_KEY, flow.getPhoneNumber(),
                "", false, "pending", null, linkTarget, clickToken);
        String shortLink = publicBaseUrl + "/r/" + clickToken;
        String body;
        if (!positive) {
            // Empathy-first, ownership-signed — a service-recovery framing rather than a flat
            // "we'd love to hear more" (see the SMS lifecycle audit). The review-gating question
            // (should a low rater ever also get the Google review link, not just this form) is a
            // routing decision above this method, not a copy one — left untouched deliberately.
            body = "I'm really sorry today wasn't a 5 for you 💛 I'd love to make it right personally. "
                    + "Reply and tell me what happened, or share details here if that's easier: "
                    + shortLink + " -Lucy, Manager";
        } else if (repeatReviewer) {
            // "Clicked through" is the only fact hasClickedLinkTarget actually establishes — we
            // have no way to confirm they went on to submit a review on Google's own page, so the
            // copy shouldn't claim they definitely left one.
            body = "So glad you loved it again! 💕 You've already clicked through to leave us a Google "
                    + "review before. If there's any specific feedback this time, we'd love to hear it "
                    + "here: " + shortLink + " -AK.LUX.NAILS";
        } else {
            body = "Yay, so happy to hear that! 🎉 Since you loved it, mind leaving a quick Google review? "
                    + "Takes 10 seconds and really helps our small business: " + shortLink + " -Lucy";
        }

        taskScheduler.schedule(() -> sendNow(flow, reserved, body), Instant.now().plus(REPLY_DELAY));
    }

    private void sendNow(SmsReplyFlow flow, SmsMessage reserved, String body) {
        TwilioSmsConfig config = configService.get();
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
