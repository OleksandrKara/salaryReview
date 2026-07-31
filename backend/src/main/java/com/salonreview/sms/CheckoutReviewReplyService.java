package com.salonreview.sms;

import com.salonreview.domain.SmsMessage;
import com.salonreview.domain.SmsReplyFlow;
import com.salonreview.domain.TwilioSmsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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

    private final SmsMessageLogService messageLogService;
    private final TwilioSmsConfigService configService;
    private final TwilioSmsClient client;
    private final String publicBaseUrl;

    public CheckoutReviewReplyService(SmsMessageLogService messageLogService, TwilioSmsConfigService configService,
                                       TwilioSmsClient client, @Value("${app.public-base-url}") String publicBaseUrl) {
        this.messageLogService = messageLogService;
        this.configService = configService;
        this.client = client;
        this.publicBaseUrl = publicBaseUrl;
    }

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
            body = "Thanks for letting us know. We'd love to hear more so we can do better: " + shortLink + " — AK.LUX.NAILS";
        } else if (repeatReviewer) {
            body = "So glad you loved it again! 💕 You've already shared a review with us — if you have any specific feedback, we'd love to hear it here: " + shortLink + " — AK.LUX.NAILS";
        } else {
            body = "Thank you so much! 🌟 We'd love it if you could share your experience: " + shortLink + " — AK.LUX.NAILS";
        }

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
