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
    /** See openspec/changes/lapsed-customer-winback-automation design.md D9 — the $5 coupon's own
     * promo code, enrolling into a separate Square group from {@link #REBOOK_PROMO_CODE}'s. */
    private static final String WINBACK_PROMO_CODE = "WINBACK5";

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
     * staff Telegram alert (see design.md D7) — never trusted for anything security-relevant.
     * {@code promoCode} is nullable for backward compatibility with callers built before
     * lapsed-customer-winback-automation existed — {@code null} defaults to {@link #REBOOK_PROMO_CODE}
     * (see {@link #resolvePromoCode}), the only promo this endpoint supported at first. */
    public record RebookingPromoEnrollRequest(String squareCustomerId, long expEpochSeconds, String signature,
                                              String customerName, String phoneNumber, String appointmentStartAt,
                                              String promoCode) {
    }

    @PostMapping("/rebooking-promo/enroll")
    public ResponseEntity<Map<String, Object>> enrollRebookingPromo(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String key,
            @RequestBody RebookingPromoEnrollRequest body) {
        if (!keyMatches(key)) {
            return ResponseEntity.status(401).build();
        }
        String promoCode = resolvePromoCode(body.promoCode());
        if (!promoSigner.verify(promoCode, body.expEpochSeconds(), body.signature())) {
            return ResponseEntity.ok(Map.of("enrolled", false, "reason", "invalid_signature"));
        }
        Instant expiresAt = Instant.ofEpochSecond(body.expEpochSeconds());
        if (expiresAt.isBefore(Instant.now())) {
            return ResponseEntity.ok(Map.of("enrolled", false, "reason", "expired"));
        }
        String groupId = groupIdForPromoCode(promoCode);
        if (groupId == null) {
            return ResponseEntity.ok(Map.of("enrolled", false, "reason", "not_configured"));
        }
        try {
            square.addCustomerToGroup(body.squareCustomerId(), groupId);
        } catch (RuntimeException e) {
            log.warn("Failed to enroll customer {} in {} group: {}", body.squareCustomerId(), promoCode, e.getMessage());
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

    /** {@code null}/blank {@code requested} defaults to {@link #REBOOK_PROMO_CODE} — see the
     * backward-compatibility note on {@link RebookingPromoEnrollRequest#promoCode}. */
    private static String resolvePromoCode(String requested) {
        return (requested == null || requested.isBlank()) ? REBOOK_PROMO_CODE : requested;
    }

    /** {@code null} for an unrecognized code, or a recognized one whose Square Catalog group
     * hasn't been set up yet (see openspec/changes/lapsed-customer-winback-automation design.md
     * D9) — both cases resolve to the same {@code not_configured}/{@code invalid_signature}
     * (never enrolled) outcome above, never a partial or guessed enrollment. */
    private String groupIdForPromoCode(String promoCode) {
        if (REBOOK_PROMO_CODE.equals(promoCode)) {
            return rebookingProperties.isAutoDiscountConfigured() ? rebookingProperties.getAutoDiscountGroupId() : null;
        }
        if (WINBACK_PROMO_CODE.equals(promoCode)) {
            return rebookingProperties.isWinbackAutoDiscountConfigured() ? rebookingProperties.getWinbackAutoDiscountGroupId() : null;
        }
        return null;
    }

    private boolean keyMatches(String provided) {
        String expected = internalApi.getKey();
        if (expected == null || expected.isBlank() || provided == null) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8));
    }
}
