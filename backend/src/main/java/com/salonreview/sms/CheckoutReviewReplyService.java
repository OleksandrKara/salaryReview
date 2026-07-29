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
 * {@link SmsTemplateRegistry}: each message's body must contain a short link keyed by its *own*
 * {@code sms_message.id}, which doesn't exist until the row is inserted — a genuine chicken-and-
 * egg the generic template-then-send flow isn't built for. Instead: reserve a placeholder row
 * (getting its id), render the real body using that id, update the row, then send. Both branches
 * are TRANSACTIONAL (see design.md D5) and sent unconditionally once a flow reaches
 * {@code AWAITING_REPLY} — disabling the automation only stops *new* flows from being enqueued,
 * it doesn't leave an already-replying customer hanging (see tasks.md 10.4).
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
        String templateKey = positive ? "checkout_review_positive" : "checkout_review_negative";
        String linkTarget = positive ? CheckoutReviewLinks.GOOGLE_REVIEW_TARGET : CheckoutReviewLinks.FEEDBACK_FORM_TARGET;

        SmsMessage reserved = messageLogService.logOutboundWithLink(
                templateKey, AUTOMATION_KEY, flow.getPhoneNumber(),
                "", false, "pending", null, linkTarget);
        String shortLink = publicBaseUrl + "/r/" + reserved.getId();
        String body = positive
                ? "Thank you so much! 🌟 We'd love it if you could share your experience: " + shortLink + " — AK.LUX.NAILS"
                : "Thanks for letting us know. We'd love to hear more so we can do better: " + shortLink + " — AK.LUX.NAILS";

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
