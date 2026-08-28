package com.salonreview.square.webhook;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.salonreview.config.SquareWebhookProperties;
import com.salonreview.repo.BusinessRepository;
import com.salonreview.square.SquareConnectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;

/**
 * Receives Square's {@code payment.updated} webhook — the checkout-review-request automation's
 * trigger (see openspec/changes/sms-automations-hub, design.md D1/D2). {@code permitAll()} in
 * {@link com.salonreview.config.SecurityConfig}; auth is the HMAC signature check below, not a
 * session (Square has none).
 *
 * <p>Phase 3.6: two routes, two key sources. {@code /api/public/webhooks/square} is Business A's
 * original, already-configured-in-Square's-dashboard subscription — left completely unchanged
 * (global {@link SquareWebhookProperties} key, resolves {@code legacySmsBusiness()}) so its real
 * production webhook keeps working with zero disruption. {@code
 * /api/public/webhooks/square/{businessId}} is the real per-business route for every other
 * business: it only ever accepts a request signed with *that* business's own
 * {@code square_connection.webhook_signature_key_encrypted} — never the legacy global key, never
 * another business's key. A business with no key configured yet 404s (nothing set up), not 401
 * (wrong key) — a deliberately different signal for debugging.
 */
@RestController
public class SquareWebhookController {

    private static final Logger log = LoggerFactory.getLogger(SquareWebhookController.class);

    private final SquareWebhookProperties properties;
    private final CheckoutReviewTriggerService triggerService;
    private final SquareBookingWebhookHandler bookingWebhookHandler;
    private final SquareOrderWebhookHandler orderWebhookHandler;
    private final BusinessRepository businesses;
    private final SquareConnectionService connectionService;
    private final String publicBaseUrl;
    private final ObjectMapper mapper = JsonMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .build();

    public SquareWebhookController(SquareWebhookProperties properties, CheckoutReviewTriggerService triggerService,
                                    SquareBookingWebhookHandler bookingWebhookHandler,
                                    SquareOrderWebhookHandler orderWebhookHandler,
                                    BusinessRepository businesses, SquareConnectionService connectionService,
                                    @Value("${app.public-base-url}") String publicBaseUrl) {
        this.properties = properties;
        this.triggerService = triggerService;
        this.bookingWebhookHandler = bookingWebhookHandler;
        this.orderWebhookHandler = orderWebhookHandler;
        this.businesses = businesses;
        this.connectionService = connectionService;
        this.publicBaseUrl = publicBaseUrl;
    }

    /** Business A's legacy, already-live subscription — unchanged behavior, global key. */
    @PostMapping("/api/public/webhooks/square")
    public ResponseEntity<Void> receive(@RequestHeader(value = "x-square-hmacsha256-signature", required = false) String signature,
                                         @RequestBody String rawBody) {
        if (!properties.isConfigured()
                || !signatureValid(properties.getSignatureKey(), properties.getNotificationUrl(), signature, rawBody)) {
            log.warn("Square webhook rejected — missing/invalid signature");
            return ResponseEntity.status(401).build();
        }
        return process(rawBody, businesses.legacySmsBusiness().getId());
    }

    /** Real per-business route — see the class doc for why this never falls back to the legacy key. */
    @PostMapping("/api/public/webhooks/square/{businessId}")
    public ResponseEntity<Void> receiveForBusiness(@PathVariable Long businessId,
                                                    @RequestHeader(value = "x-square-hmacsha256-signature", required = false) String signature,
                                                    @RequestBody String rawBody) {
        Optional<String> key = connectionService.getWebhookSignatureKey(businessId);
        if (key.isEmpty()) {
            log.warn("Square webhook rejected for business {} — no webhook signature key configured", businessId);
            return ResponseEntity.status(404).build();
        }
        String notificationUrl = publicBaseUrl + "/api/public/webhooks/square/" + businessId;
        if (!signatureValid(key.get(), notificationUrl, signature, rawBody)) {
            log.warn("Square webhook rejected for business {} — missing/invalid signature", businessId);
            return ResponseEntity.status(401).build();
        }
        return process(rawBody, businessId);
    }

    private ResponseEntity<Void> process(String rawBody, Long businessId) {
        SquareWebhookEvent event;
        try {
            event = mapper.readValue(rawBody, SquareWebhookEvent.class);
        } catch (Exception e) {
            log.warn("Square webhook payload unparseable, ignoring: {}", e.getMessage());
            return ResponseEntity.ok().build();
        }

        SquareWebhookEvent.Data.DataObject object = event.data() == null ? null : event.data().object();
        if (object != null && object.payment() != null) {
            triggerService.handlePaymentUpdated(businessId, object.payment());
        }
        // Square-data mirror (see the Phase 1 sync plan) — independent listeners on the same
        // event stream above, not entangled with the checkout-review trigger's own payment handling.
        if (object != null && object.booking() != null) {
            bookingWebhookHandler.handleBookingEvent(businessId, object.booking());
        }
        if (object != null && object.orderUpdated() != null) {
            orderWebhookHandler.handleOrderUpdated(businessId, object.orderUpdated());
        }
        return ResponseEntity.ok().build();
    }

    /** {@code x-square-hmacsha256-signature} = base64(HMAC-SHA256(signatureKey, notificationUrl +
     * rawBody)) — Square's documented validation scheme. Constant-time compared, same reasoning as
     * {@code InternalNotificationController.keyMatches}. Takes the key/URL explicitly rather than
     * reading {@link #properties} directly so both routes share this one implementation. */
    private boolean signatureValid(String key, String notificationUrl, String signature, String rawBody) {
        if (signature == null || signature.isBlank() || key == null || key.isBlank()) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] computed = mac.doFinal((notificationUrl + rawBody).getBytes(StandardCharsets.UTF_8));
            String expected = Base64.getEncoder().encodeToString(computed);
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("Square webhook signature check failed: {}", e.getMessage());
            return false;
        }
    }
}
