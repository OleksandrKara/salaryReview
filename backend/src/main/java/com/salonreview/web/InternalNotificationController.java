package com.salonreview.web;

import com.salonreview.config.InternalApiProperties;
import com.salonreview.domain.Business;
import com.salonreview.domain.SameDayRebookingGroupMembership;
import com.salonreview.repo.SameDayRebookingGroupMembershipRepository;
import com.salonreview.sms.PromoConfigService;
import com.salonreview.sms.RebookingPromoSigner;
import com.salonreview.repo.BusinessRepository;
import com.salonreview.sms.TwilioSmsService;
import com.salonreview.square.SquareClientProvider;
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
import java.util.Optional;

/**
 * Service-to-service endpoints for mani/akluxnails-home, gated by a shared {@code X-Internal-Api-Key}
 * header instead of a session (these callers have no user login). Listed as {@code permitAll()} in
 * {@link com.salonreview.config.SecurityConfig} — auth is enforced here, not by Spring Security.
 */
@RestController
@RequestMapping("/api/internal")
public class InternalNotificationController {

    private static final Logger log = LoggerFactory.getLogger(InternalNotificationController.class);

    private final InternalApiProperties internalApi;
    private final TelegramNotificationService telegram;
    private final TwilioSmsService sms;
    private final RebookingPromoSigner promoSigner;
    private final PromoConfigService promoConfigService;
    private final SameDayRebookingGroupMembershipRepository groupMembershipRepository;
    private final SquareClientProvider squareClientProvider;
    private final BusinessRepository businesses;

    public InternalNotificationController(InternalApiProperties internalApi, TelegramNotificationService telegram,
                                          TwilioSmsService sms, RebookingPromoSigner promoSigner,
                                          PromoConfigService promoConfigService,
                                          SameDayRebookingGroupMembershipRepository groupMembershipRepository,
                                          SquareClientProvider squareClientProvider, BusinessRepository businesses) {
        this.internalApi = internalApi;
        this.telegram = telegram;
        this.sms = sms;
        this.promoSigner = promoSigner;
        this.promoConfigService = promoConfigService;
        this.groupMembershipRepository = groupMembershipRepository;
        this.squareClientProvider = squareClientProvider;
        this.businesses = businesses;
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
        // See BusinessRepository#legacySmsBusiness, same as the SMS schedulers — this endpoint has
        // no session, so there's no business context to resolve beyond Business A.
        TwilioSmsService.SmsSendResult result = sms.sendTemplated(
                businesses.legacySmsBusiness().getId(), body.templateKey(), body.phoneNumber(), body.variables());
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
     * lapsed-customer-winback-automation existed — {@code null} defaults to
     * {@link PromoConfigService#REBOOK_PROMO_CODE} (see {@link #resolvePromoCode}), the only promo
     * this endpoint supported at first. {@code businessShortCode}/{@code businessId} are both
     * nullable for the same reason — akluxnails-home (the only caller before a second business
     * existed) sends neither, which resolves to {@link BusinessRepository#legacySmsBusiness}, same
     * as every other caller here had before these fields existed. A second business's landing page
     * must send one — {@code businessId} takes priority when both are present (salonLandings's own
     * {@code BusinessContext} already carries the numeric id from its domain lookup, no extra
     * short-code plumbing needed there). */
    public record RebookingPromoEnrollRequest(String squareCustomerId, long expEpochSeconds, String signature,
                                              String customerName, String phoneNumber, String appointmentStartAt,
                                              String promoCode, String businessShortCode, Long businessId) {
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
        Business business = resolveBusiness(body.businessShortCode(), body.businessId());
        if (business == null) {
            return ResponseEntity.ok(Map.of("enrolled", false, "reason", "unknown_business"));
        }
        Long businessId = business.getId();
        Optional<PromoConfigService.PromoTerms> terms = promoConfigService.get(businessId, promoCode);
        if (terms.isEmpty()) {
            return ResponseEntity.ok(Map.of("enrolled", false, "reason", "not_configured"));
        }
        String groupId = terms.get().squareCustomerGroupId();
        try {
            squareClientProvider.forBusiness(businessId)
                    .addCustomerToGroup(body.squareCustomerId(), groupId);
        } catch (RuntimeException e) {
            log.warn("Failed to enroll customer {} in {} group: {}", body.squareCustomerId(), promoCode, e.getMessage());
            return ResponseEntity.ok(Map.of("enrolled", false, "reason", "square_error"));
        }
        groupMembershipRepository.save(SameDayRebookingGroupMembership.builder()
                .businessId(businessId)
                .squareCustomerId(body.squareCustomerId())
                .groupId(groupId)
                .expiresAt(expiresAt)
                .build());
        // Best-effort, doesn't affect the "enrolled" outcome above — matches how every other
        // notification in this codebase is decoupled from the primary action it accompanies.
        telegram.sendRebookingPromoAlert(body.customerName(), body.phoneNumber(), body.appointmentStartAt());
        return ResponseEntity.ok(Map.of("enrolled", true));
    }

    /** For a landing page's promo banner: verifies the promo/exp/sig query params it loaded with
     * AND returns the live discount amount/minimum spend, resolved fresh from
     * {@link PromoConfigService} at page-render time rather than baked into the signed link (an
     * owner's amount edit takes effect on the next click, same as {@code ShortLinkController}).
     * Deliberately the only place that ever checks {@link RebookingPromoSigner} outside this app —
     * no landing-page deployment needs its own copy of the signing secret; it forwards the raw
     * query params here over the same {@code X-Internal-Api-Key} channel every other internal call
     * already uses. {@code valid: false} covers every failure the same way (bad signature, expired,
     * unrecognized business, or the business simply hasn't configured this promo) — the landing
     * page shows no banner and sends no promo through on booking either way, no need to
     * distinguish why. */
    @GetMapping("/rebooking-promo/verify")
    public ResponseEntity<Map<String, Object>> verifyRebookingPromo(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String key,
            @RequestParam String promoCode, @RequestParam long expEpochSeconds, @RequestParam String signature,
            @RequestParam(required = false) String businessShortCode, @RequestParam(required = false) Long businessId) {
        if (!keyMatches(key)) {
            return ResponseEntity.status(401).build();
        }
        String code = resolvePromoCode(promoCode);
        if (!promoSigner.verify(code, expEpochSeconds, signature) || Instant.ofEpochSecond(expEpochSeconds).isBefore(Instant.now())) {
            return ResponseEntity.ok(Map.of("valid", false));
        }
        Business business = resolveBusiness(businessShortCode, businessId);
        if (business == null) {
            return ResponseEntity.ok(Map.of("valid", false));
        }
        Optional<PromoConfigService.PromoTerms> terms = promoConfigService.get(business.getId(), code);
        if (terms.isEmpty()) {
            return ResponseEntity.ok(Map.of("valid", false));
        }
        Map<String, Object> response = new HashMap<>();
        response.put("valid", true);
        response.put("discountCents", terms.get().discountCents());
        response.put("minSpendCents", terms.get().minSpendCents());
        return ResponseEntity.ok(response);
    }

    /** {@code null}/blank {@code requested} defaults to {@link PromoConfigService#REBOOK_PROMO_CODE}
     * — see the backward-compatibility note on {@link RebookingPromoEnrollRequest#promoCode}. */
    private static String resolvePromoCode(String requested) {
        return (requested == null || requested.isBlank()) ? PromoConfigService.REBOOK_PROMO_CODE : requested;
    }

    /** {@code businessId} wins when present (see {@link RebookingPromoEnrollRequest#businessId}).
     * Otherwise {@code null}/blank {@code shortCode} resolves to Business A — see the backward-
     * compatibility note on {@link RebookingPromoEnrollRequest#businessShortCode}. An unrecognized
     * non-blank identifier returns {@code null} (never silently falls back to Business A — a
     * second business's misconfigured deployment must fail loudly, not enroll into the wrong
     * salon's Square account). */
    private Business resolveBusiness(String shortCode, Long businessId) {
        if (businessId != null) {
            return businesses.findById(businessId).orElse(null);
        }
        if (shortCode == null || shortCode.isBlank()) {
            return businesses.legacySmsBusiness();
        }
        return businesses.findByShortCode(shortCode).orElse(null);
    }

    private boolean keyMatches(String provided) {
        String expected = internalApi.getKey();
        if (expected == null || expected.isBlank() || provided == null) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8));
    }
}
