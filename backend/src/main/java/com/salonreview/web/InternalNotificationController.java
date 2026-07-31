package com.salonreview.web;

import com.salonreview.config.InternalApiProperties;
import com.salonreview.config.RebookingProperties;
import com.salonreview.domain.SameDayRebookingGroupMembership;
import com.salonreview.repo.SameDayRebookingGroupMembershipRepository;
import com.salonreview.sms.RebookingPromoSigner;
import com.salonreview.sms.TwilioSmsService;
import com.salonreview.square.SquareClient;
import com.salonreview.telegram.FourHandRequestNotification;
import com.salonreview.telegram.TelegramNotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
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

    private static final Logger log = LoggerFactory.getLogger(InternalNotificationController.class);
    private static final String REBOOK_PROMO_CODE = "REBOOK10";

    private final InternalApiProperties internalApi;
    private final TelegramNotificationService telegram;
    private final TwilioSmsService sms;
    private final RebookingPromoSigner promoSigner;
    private final RebookingProperties rebookingProperties;
    private final SameDayRebookingGroupMembershipRepository groupMembershipRepository;
    private final SquareClient square;

    public InternalNotificationController(InternalApiProperties internalApi, TelegramNotificationService telegram,
                                          TwilioSmsService sms, RebookingPromoSigner promoSigner,
                                          RebookingProperties rebookingProperties,
                                          SameDayRebookingGroupMembershipRepository groupMembershipRepository,
                                          SquareClient square) {
        this.internalApi = internalApi;
        this.telegram = telegram;
        this.sms = sms;
        this.promoSigner = promoSigner;
        this.rebookingProperties = rebookingProperties;
        this.groupMembershipRepository = groupMembershipRepository;
        this.square = square;
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

    /** {@code expEpochSeconds}/{@code signature} are re-verified here independently of whatever
     * akluxnails-home's own page-render check already did — see
     * openspec/changes/same-day-rebooking-discount design.md D8. A caller could in principle hit
     * this endpoint directly with a hand-crafted request, bypassing the UI entirely, so the
     * signature — not "the caller says it verified" — is what actually gates enrollment.
     * {@code customerName}/{@code phoneNumber}/{@code appointmentStartAt} are only used for the
     * staff Telegram alert (see design.md D7) — never trusted for anything security-relevant. */
    public record RebookingPromoEnrollRequest(String squareCustomerId, long expEpochSeconds, String signature,
                                              String customerName, String phoneNumber, String appointmentStartAt) {
    }

    @PostMapping("/rebooking-promo/enroll")
    public ResponseEntity<Map<String, Object>> enrollRebookingPromo(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String key,
            @RequestBody RebookingPromoEnrollRequest body) {
        if (!keyMatches(key)) {
            return ResponseEntity.status(401).build();
        }
        if (!promoSigner.verify(REBOOK_PROMO_CODE, body.expEpochSeconds(), body.signature())) {
            return ResponseEntity.ok(Map.of("enrolled", false, "reason", "invalid_signature"));
        }
        Instant expiresAt = Instant.ofEpochSecond(body.expEpochSeconds());
        if (expiresAt.isBefore(Instant.now())) {
            return ResponseEntity.ok(Map.of("enrolled", false, "reason", "expired"));
        }
        if (!rebookingProperties.isAutoDiscountConfigured()) {
            return ResponseEntity.ok(Map.of("enrolled", false, "reason", "not_configured"));
        }
        try {
            square.addCustomerToGroup(body.squareCustomerId(), rebookingProperties.getAutoDiscountGroupId());
        } catch (RuntimeException e) {
            log.warn("Failed to enroll customer {} in same-day-rebooking group: {}", body.squareCustomerId(), e.getMessage());
            return ResponseEntity.ok(Map.of("enrolled", false, "reason", "square_error"));
        }
        groupMembershipRepository.save(SameDayRebookingGroupMembership.builder()
                .squareCustomerId(body.squareCustomerId())
                .expiresAt(expiresAt)
                .build());
        // Best-effort, doesn't affect the "enrolled" outcome above — matches how every other
        // notification in this codebase is decoupled from the primary action it accompanies.
        telegram.sendRebookingPromoAlert(body.customerName(), body.phoneNumber(), body.appointmentStartAt());
        return ResponseEntity.ok(Map.of("enrolled", true));
    }

    private boolean keyMatches(String provided) {
        String expected = internalApi.getKey();
        if (expected == null || expected.isBlank() || provided == null) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8));
    }
}
