package com.salonreview.web;

import com.salonreview.config.InternalApiProperties;
import com.salonreview.sms.PromoConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Set;

/** Host-side setup of a business's promo terms (creates the Square customer group, discount and
 * pricing rule on first use, see PromoConfigService#save), for operators without an owner login
 * session. Only the codes listed here; same X-Internal-Api-Key gate as every /api/internal call. */
@RestController
@RequestMapping("/api/internal/promos")
public class InternalPromoController {

    private static final Set<String> CODES = Set.of(PromoConfigService.CONSULTATION_OFFER_PROMO_CODE);

    private final InternalApiProperties internalApi;
    private final PromoConfigService promoConfigService;

    public InternalPromoController(InternalApiProperties internalApi, PromoConfigService promoConfigService) {
        this.internalApi = internalApi;
        this.promoConfigService = promoConfigService;
    }

    @PutMapping("/{businessId}/{promoCode}")
    public ResponseEntity<Map<String, Object>> save(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String key,
            @PathVariable Long businessId, @PathVariable String promoCode,
            @RequestParam long discountCents, @RequestParam(required = false) Long minSpendCents) {
        if (!keyMatches(key)) {
            return ResponseEntity.status(401).build();
        }
        if (!CODES.contains(promoCode)) {
            return ResponseEntity.badRequest().body(Map.of("error", "unsupported promo code"));
        }
        PromoConfigService.PromoTerms terms = promoConfigService.save(businessId, promoCode, discountCents, minSpendCents, "internal-api");
        return ResponseEntity.ok(Map.of("discountCents", terms.discountCents(),
                "minSpendCents", terms.minSpendCents() == null ? 0 : terms.minSpendCents(),
                "configured", terms.configured()));
    }

    private boolean keyMatches(String provided) {
        String expected = internalApi.getKey();
        if (expected == null || expected.isBlank() || provided == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8));
    }
}
