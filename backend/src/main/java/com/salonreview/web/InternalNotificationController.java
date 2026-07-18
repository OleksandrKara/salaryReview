package com.salonreview.web;

import com.salonreview.config.InternalApiProperties;
import com.salonreview.telegram.FourHandRequestNotification;
import com.salonreview.telegram.TelegramNotificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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

    public InternalNotificationController(InternalApiProperties internalApi, TelegramNotificationService telegram) {
        this.internalApi = internalApi;
        this.telegram = telegram;
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

    private boolean keyMatches(String provided) {
        String expected = internalApi.getKey();
        if (expected == null || expected.isBlank() || provided == null) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8));
    }
}
