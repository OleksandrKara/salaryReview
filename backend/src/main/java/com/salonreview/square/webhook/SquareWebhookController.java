package com.salonreview.square.webhook;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.salonreview.config.SquareWebhookProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Receives Square's {@code payment.updated} webhook — the checkout-review-request automation's
 * trigger (see openspec/changes/sms-automations-hub, design.md D1/D2). {@code permitAll()} in
 * {@link com.salonreview.config.SecurityConfig}; auth is the HMAC signature check below, not a
 * session (Square has none).
 */
@RestController
public class SquareWebhookController {

    private static final Logger log = LoggerFactory.getLogger(SquareWebhookController.class);

    private final SquareWebhookProperties properties;
    private final CheckoutReviewTriggerService triggerService;
    private final ObjectMapper mapper = JsonMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .build();

    public SquareWebhookController(SquareWebhookProperties properties, CheckoutReviewTriggerService triggerService) {
        this.properties = properties;
        this.triggerService = triggerService;
    }

    @PostMapping("/api/public/webhooks/square")
    public ResponseEntity<Void> receive(@RequestHeader(value = "x-square-hmacsha256-signature", required = false) String signature,
                                         @RequestBody String rawBody) {
        if (!properties.isConfigured() || !signatureValid(signature, rawBody)) {
            log.warn("Square webhook rejected — missing/invalid signature");
            return ResponseEntity.status(401).build();
        }

        SquareWebhookEvent event;
        try {
            event = mapper.readValue(rawBody, SquareWebhookEvent.class);
        } catch (Exception e) {
            log.warn("Square webhook payload unparseable, ignoring: {}", e.getMessage());
            return ResponseEntity.ok().build();
        }

        if (event.data() != null && event.data().object() != null && event.data().object().payment() != null) {
            triggerService.handlePaymentUpdated(event.data().object().payment());
        }
        return ResponseEntity.ok().build();
    }

    /** {@code x-square-hmacsha256-signature} = base64(HMAC-SHA256(signatureKey, notificationUrl +
     * rawBody)) — Square's documented validation scheme. Constant-time compared, same reasoning as
     * {@code InternalNotificationController.keyMatches}. */
    private boolean signatureValid(String signature, String rawBody) {
        if (signature == null || signature.isBlank()) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getSignatureKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] computed = mac.doFinal((properties.getNotificationUrl() + rawBody).getBytes(StandardCharsets.UTF_8));
            String expected = Base64.getEncoder().encodeToString(computed);
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("Square webhook signature check failed: {}", e.getMessage());
            return false;
        }
    }
}
