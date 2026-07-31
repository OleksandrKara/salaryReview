package com.salonreview.sms;

import com.salonreview.config.TwilioInboundProperties;
import com.salonreview.domain.SmsMessage;
import com.salonreview.domain.SmsReplyFlow;
import com.salonreview.repo.SmsReplyFlowRepository;
import com.salonreview.telegram.TelegramNotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

    public TwilioInboundSmsController(TwilioInboundProperties properties, SmsMessageLogService messageLogService,
                                       SmsReplyFlowRepository replyFlowRepository, CheckoutReviewReplyService replyService,
                                       TelegramNotificationService telegramService) {
        this.properties = properties;
        this.messageLogService = messageLogService;
        this.replyFlowRepository = replyFlowRepository;
        this.replyService = replyService;
        this.telegramService = telegramService;
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
        SmsMessage logged = messageLogService.logInbound(from, body, pending.map(SmsReplyFlow::getAutomationKey).orElse(null));
        logged.setTwilioMessageSid(params.get("MessageSid"));
        messageLogService.save(logged);

        // A customer reply always needs a human's attention right away, not just a dashboard entry
        // nobody's actively watching — see openspec/changes/sms-automations-hub proposal.md.
        telegramService.sendInboundSmsAlert(from, body, logged.getAutomationKey());

        if (pending.isPresent()) {
            SmsReplyFlow flow = pending.get();
            boolean positive = body.contains("5"); // digits only — "Five" spelled out doesn't match, see design.md D4
            replyService.sendBranchReply(flow, positive);
            flow.setState(SmsReplyFlow.STATE_COMPLETED);
            replyFlowRepository.save(flow);
        }
        return ResponseEntity.ok().build();
    }
}
