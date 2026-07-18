package com.salonreview.web;

import com.salonreview.config.InternalApiProperties;
import com.salonreview.sms.TwilioSmsService;
import com.salonreview.telegram.FourHandRequestNotification;
import com.salonreview.telegram.TelegramNotificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/**
 * Service-to-service endpoints for mani/akluxnails-home, gated by a shared {@code X-Internal-Api-Key}
 * header instead of a session (these callers have no user login). Listed as {@code permitAll()} in
 * {@link com.salonreview.config.SecurityConfig} — auth is enforced here, not by Spring Security.
 */
@RestController
@RequestMapping("/api/internal")
public class InternalNotificationController {

    private final InternalApiProperties internalApi;
    private final TelegramNotificationService telegram;
    private final TwilioSmsService sms;

    public InternalNotificationController(InternalApiProperties internalApi, TelegramNotificationService telegram,
                                          TwilioSmsService sms) {
        this.internalApi = internalApi;
        this.telegram = telegram;
        this.sms = sms;
    }

    @PostMapping("/notifications/four-hand-request")
    public ResponseEntity<Map<String, Object>> notifyFourHand(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String key,
            @RequestBody FourHandRequestNotification body) {
        if (!keyMatches(key)) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok(Map.of("sent", telegram.sendFourHandRequestAlert(body)));
    }

    /** {@code messageClass} is deliberately not a field on {@link SmsSendRequest} — it is fixed
     * per {@code templateKey} inside {@link TwilioSmsService}, never accepted from a caller. */
    public record SmsSendRequest(String templateKey, String phoneNumber, Map<String, String> variables) {
    }

    @PostMapping("/notifications/sms/send")
    public ResponseEntity<Map<String, Object>> sendSms(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String key,
            @RequestBody SmsSendRequest body) {
        if (!keyMatches(key)) {
            return ResponseEntity.status(401).build();
        }
        TwilioSmsService.SmsSendResult result = sms.sendTemplated(body.templateKey(), body.phoneNumber(), body.variables());
        Map<String, Object> response = new HashMap<>();
        response.put("sent", result.sent());
        response.put("reason", result.reason());
        return ResponseEntity.ok(response);
    }

    private boolean keyMatches(String provided) {
        String expected = internalApi.getKey();
        if (expected == null || expected.isBlank() || provided == null) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8));
    }
}
