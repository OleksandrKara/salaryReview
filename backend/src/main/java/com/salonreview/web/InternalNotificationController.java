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
import com.salonreview.telegram.ConsultationRequestNotification;
import com.salonreview.telegram.FourHandRequestNotification;
import com.salonreview.telegram.PaymentFailedNotification;
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
    private final com.salonreview.sms.VipRebookEligibilityService vipRebookEligibility;

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
                                          SquareClientProvider squareClientProvider, BusinessRepository businesses,
                                          com.salonreview.sms.VipRebookEligibilityService vipRebookEligibility) {
        this.vipRebookEligibility = vipRebookEligibility;
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
        Business business = resolveBusiness(body.businessShortCode(), body.businessId());
        Long businessId = business != null ? business.getId() : businesses.legacySmsBusiness().getId();
        return ResponseEntity.ok(Map.of("sent", telegram.sendFourHandRequestAlert(businessId, body)));
    }

    /** {@code messageClass} is deliberately not a field on {@link SmsSendRequest} — it is fixed
     * per {@code templateKey} inside {@link TwilioSmsService}, never accepted from a caller.
     * {@code businessShortCode}/{@code businessId} mirror {@link RebookingPromoEnrollRequest}'s
     * own fields exactly, resolved the same way via {@link #resolveBusiness} — both nullable for
     * backward compatibility with mani's existing caller (which sends neither), which resolves to
     * {@link BusinessRepository#legacySmsBusiness}, its unchanged prior behavior. */
    public record SmsSendRequest(String templateKey, String phoneNumber, Map<String, String> variables,
                                  String businessShortCode, Long businessId) {
    }

    @PostMapping("/notifications/sms/send")
    public ResponseEntity<Map<String, Object>> sendSms(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String key,
            @RequestBody SmsSendRequest body) {
        if (!keyMatches(key)) {
            return ResponseEntity.status(401).build();
        }
        Business business = resolveBusiness(body.businessShortCode(), body.businessId());
        if (business == null) {
            return ResponseEntity.ok(Map.of("sent", false, "reason", "unknown_business"));
        }
        TwilioSmsService.SmsSendResult result = sms.sendTemplated(
                business.getId(), body.templateKey(), body.phoneNumber(), body.variables());
        Map<String, Object> response = new HashMap<>();
        response.put("sent", result.sent());
        response.put("reason", result.reason());
        return ResponseEntity.ok(response);
    }

    /** Fired the moment a customer-entered card fails to charge, regardless of which business's
     * landing page collected it (today only PMU's deposit-first flow — see
     * SquarePaymentGateway's own doc comment in salonLandings — but any future card-collecting
     * flow for any business posts here the same way). {@code businessShortCode}/{@code businessId}
     * resolved via {@link #resolveBusiness}, same both-nullable convention as every other request
     * record here. Never blocks or fails loudly — same "sent: false" outcome whether the business
     * couldn't be resolved or the Telegram config isn't set up, since the caller (already handling
     * its own real payment failure) has nothing useful to do differently either way. */
    public record PaymentFailedRequest(String customerName, String phoneNumber, String serviceName, Double amount,
                                        String errorMessage, String errorCode, boolean clientError,
                                        String businessShortCode, Long businessId) {
    }

    @PostMapping("/notifications/payment-failed")
    public ResponseEntity<Map<String, Object>> notifyPaymentFailed(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String key,
            @RequestBody PaymentFailedRequest body) {
        if (!keyMatches(key)) {
            return ResponseEntity.status(401).build();
        }
        Business business = resolveBusiness(body.businessShortCode(), body.businessId());
        if (business == null) {
            return ResponseEntity.ok(Map.of("sent", false, "reason", "unknown_business"));
        }
        PaymentFailedNotification notification = new PaymentFailedNotification(
                business.getId(), body.businessShortCode(), body.customerName(), body.phoneNumber(),
                body.serviceName(), body.amount(), body.errorMessage(), body.errorCode(), body.clientError());
        return ResponseEntity.ok(Map.of("sent", telegram.sendPaymentFailedAlert(business.getId(), notification)));
    }

    /** akluxnails.com/card (standalone card-on-file page, 2026-10-01): a card saved, refused, or
     * the page pausing itself after a burst of failures. Fail-open like every alert here. */
    @PostMapping("/notifications/card-on-file")
    public ResponseEntity<Map<String, Object>> notifyCardOnFile(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String key,
            @RequestBody com.salonreview.telegram.CardOnFileNotification body) {
        if (!keyMatches(key)) {
            return ResponseEntity.status(401).build();
        }
        if (body.event() == null) {
            return ResponseEntity.badRequest().body(Map.of("sent", false, "reason", "missing_event"));
        }
        Business business = resolveBusiness(body.businessShortCode(), body.businessId());
        if (business == null) {
            return ResponseEntity.ok(Map.of("sent", false, "reason", "unknown_business"));
        }
        return ResponseEntity.ok(Map.of("sent", telegram.sendCardOnFileAlert(business.getId(), body)));
    }

    /** Fired the moment a customer books a free PMU consultation (Business 2's
     * {@code consultation_lead_sms} automation) — this is a second, independent leg alongside the
     * customer's own SMS confirmation (sent separately via {@link #sendSms}/{@code /sms/send}), not
     * a replacement for it: staff get a Telegram heads-up, the customer still gets their text.
     * {@code businessShortCode}/{@code businessId} resolved via {@link #resolveBusiness}, same
     * both-nullable convention as every other request record here. Same "sent: false" fail-open
     * outcome as {@link #notifyPaymentFailed} whether the business couldn't be resolved or the
     * Telegram config isn't set up — the caller (already done booking) has nothing useful to do
     * differently either way. */
    public record ConsultationRequestRequest(String customerName, String phoneNumber, String startAt,
                                              boolean online, String locationAddress, String artistName,
                                              String businessShortCode, Long businessId,
                                              String sourcePageUrl, String sourcePageTitle, String adCampaign) {
    }

    @PostMapping("/notifications/consultation-request")
    public ResponseEntity<Map<String, Object>> notifyConsultationRequest(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String key,
            @RequestBody ConsultationRequestRequest body) {
        if (!keyMatches(key)) {
            return ResponseEntity.status(401).build();
        }
        Business business = resolveBusiness(body.businessShortCode(), body.businessId());
        if (business == null) {
            return ResponseEntity.ok(Map.of("sent", false, "reason", "unknown_business"));
        }
        ConsultationRequestNotification notification = new ConsultationRequestNotification(
                business.getId(), body.businessShortCode(), body.customerName(), body.phoneNumber(),
                body.startAt(), body.online(), body.locationAddress(), body.artistName(),
                body.sourcePageUrl(), body.sourcePageTitle(), body.adCampaign());
        return ResponseEntity.ok(Map.of("sent", telegram.sendConsultationRequestAlert(business.getId(), notification)));
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
        // VIP perk: only for a next visit within 4 weeks (akluxnails-home already blocks a later
        // date before booking; this is the server-side backstop).
        if (PromoConfigService.VIP_PROMO_CODE.equals(promoCode) && !withinVipWindow(body.appointmentStartAt())) {
            return ResponseEntity.ok(Map.of("enrolled", false, "reason", "outside_window"));
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
        telegram.sendRebookingPromoAlert(businessId, body.customerName(), body.phoneNumber(), body.appointmentStartAt());
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

    private static boolean withinVipWindow(String appointmentStartAt) {
        if (appointmentStartAt == null || appointmentStartAt.isBlank()) return false;
        try {
            return Instant.parse(appointmentStartAt).isBefore(com.salonreview.sms.VipRebookEligibilityService.latestStartFor(Instant.now()));
        } catch (Exception e) {
            return false;
        }
    }

    public record VipRebookCheckRequest(String phoneNumber, String businessShortCode, Long businessId) {
    }

    /** akluxnails.com/vip: may this phone number get the VIP rebooking perk today? Yes/no plus,
     * when yes, the signed VIP10 offer and what the page needs to personalize itself. See
     * {@link com.salonreview.sms.VipRebookEligibilityService}. */
    @PostMapping("/vip-rebook/check")
    public ResponseEntity<Map<String, Object>> checkVipRebook(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String key,
            @RequestBody VipRebookCheckRequest body) {
        if (!keyMatches(key)) {
            return ResponseEntity.status(401).build();
        }
        Business business = resolveBusiness(body.businessShortCode(), body.businessId());
        if (business == null) {
            return ResponseEntity.ok(Map.of("eligible", false, "reason", "unknown_business"));
        }
        com.salonreview.sms.VipRebookEligibilityService.Result r;
        try {
            r = vipRebookEligibility.check(business.getId(), body.phoneNumber());
        } catch (RuntimeException e) {
            log.warn("VIP rebook check failed: {}", e.getMessage());
            return ResponseEntity.ok(Map.of("eligible", false, "reason", "error"));
        }
        if (!r.eligible()) {
            return ResponseEntity.ok(Map.of("eligible", false, "reason", r.reason()));
        }
        Map<String, Object> out = new HashMap<>();
        out.put("eligible", true);
        out.put("promoCode", com.salonreview.sms.PromoConfigService.VIP_PROMO_CODE);
        out.put("expEpochSeconds", r.expEpochSeconds());
        out.put("signature", r.signature());
        out.put("latestStartEpochSeconds", r.latestStartEpochSeconds());
        out.put("givenName", r.givenName());
        out.put("technicianName", r.technicianName());
        out.put("teamMemberId", r.teamMemberId());
        out.put("newClient", r.newClient());
        return ResponseEntity.ok(out);
    }

    private boolean keyMatches(String provided) {
        String expected = internalApi.getKey();
        if (expected == null || expected.isBlank() || provided == null) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8));
    }
}
