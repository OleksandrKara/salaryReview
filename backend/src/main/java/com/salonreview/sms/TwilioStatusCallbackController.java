package com.salonreview.sms;

import com.salonreview.config.TwilioInboundProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Receives Twilio's per-message delivery-status callback (queued/sending/sent/delivered/
 * undelivered/failed, plus an error code for the last two) — see delivery-status-tracking design.
 * {@code permitAll()} in {@link com.salonreview.config.SecurityConfig}; auth is the HMAC
 * signature check below, not a session (Twilio has none) — same scheme as
 * {@link TwilioInboundSmsController}.
 *
 * <p>Unlike the inbound-SMS webhook, this URL isn't configured anywhere in the Twilio Console —
 * it's passed as the {@code StatusCallback} param on every send ({@link TwilioSmsClient}), built
 * from the same {@code app.public-base-url} this controller expects the signature against, so the
 * two always agree without a second URL to keep in sync by hand.
 */
@RestController
public class TwilioStatusCallbackController {

    private static final Logger log = LoggerFactory.getLogger(TwilioStatusCallbackController.class);

    private final TwilioInboundProperties properties;
    private final SmsMessageLogService messageLogService;
    private final String webhookUrl;

    public TwilioStatusCallbackController(TwilioInboundProperties properties, SmsMessageLogService messageLogService,
                                           @Value("${app.public-base-url}") String publicBaseUrl) {
        this.properties = properties;
        this.messageLogService = messageLogService;
        this.webhookUrl = publicBaseUrl + "/api/public/sms/status";
    }

    @PostMapping(value = "/api/public/sms/status", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> receive(@RequestHeader(value = "X-Twilio-Signature", required = false) String signature,
                                         @RequestParam Map<String, String> params) {
        if (!properties.isConfigured() || !TwilioSignature.valid(properties.getAuthToken(), webhookUrl, params, signature)) {
            log.warn("Twilio status callback rejected — missing/invalid signature");
            return ResponseEntity.status(401).build();
        }

        messageLogService.updateDeliveryStatus(params.get("MessageSid"), params.get("MessageStatus"), params.get("ErrorCode"));
        return ResponseEntity.ok().build();
    }
}
