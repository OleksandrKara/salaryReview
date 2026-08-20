package com.salonreview.web;

import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.sms.PromoConfigService;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.security.Principal;
import java.util.List;
import java.util.Optional;

/**
 * Owner-editable discount amount/minimum-spend for the same-day-rebooking and lapsed/repeat
 * customer-winback SMS automations' coupon links — falls under {@code /api/owner/**} in
 * {@link com.salonreview.config.SecurityConfig}, no new security config needed. See
 * {@link PromoConfigService} for why Business A can't be edited here.
 */
@RestController
@RequestMapping("/api/owner/settings/promos")
public class PromoSettingsController {

    private final PromoConfigService promoConfigService;
    private final CurrentBusinessContext currentBusinessContext;

    public PromoSettingsController(PromoConfigService promoConfigService, CurrentBusinessContext currentBusinessContext) {
        this.promoConfigService = promoConfigService;
        this.currentBusinessContext = currentBusinessContext;
    }

    public record PromoTermsDto(String promoCode, String automationKey, String label,
                                 BigDecimal discountAmount, BigDecimal minSpend, boolean configured) {
    }

    @GetMapping
    public List<PromoTermsDto> list() {
        Long businessId = currentBusinessContext.id();
        return List.of(
                toDto(PromoConfigService.REBOOK_PROMO_CODE, "same_day_rebooking_discount", "Same-day rebooking discount",
                        promoConfigService.get(businessId, PromoConfigService.REBOOK_PROMO_CODE)),
                toDto(PromoConfigService.WINBACK_PROMO_CODE, "lapsed_customer_winback", "Customer winback discount",
                        promoConfigService.get(businessId, PromoConfigService.WINBACK_PROMO_CODE)));
    }

    public record UpdateRequest(BigDecimal discountAmount, BigDecimal minSpend) {
    }

    @PutMapping("/{promoCode}")
    public PromoTermsDto update(@PathVariable String promoCode, @RequestBody UpdateRequest body, Principal principal) {
        Long businessId = currentBusinessContext.id();
        long discountCents = body.discountAmount().movePointRight(2).longValueExact();
        Long minSpendCents = body.minSpend() == null ? null : body.minSpend().movePointRight(2).longValueExact();
        PromoConfigService.PromoTerms terms =
                promoConfigService.save(businessId, promoCode, discountCents, minSpendCents, principal.getName());
        String automationKey = PromoConfigService.REBOOK_PROMO_CODE.equals(promoCode)
                ? "same_day_rebooking_discount" : "lapsed_customer_winback";
        String label = PromoConfigService.REBOOK_PROMO_CODE.equals(promoCode)
                ? "Same-day rebooking discount" : "Customer winback discount";
        return toDto(promoCode, automationKey, label, Optional.of(terms));
    }

    private static PromoTermsDto toDto(String promoCode, String automationKey, String label,
                                        Optional<PromoConfigService.PromoTerms> terms) {
        if (terms.isEmpty()) {
            return new PromoTermsDto(promoCode, automationKey, label, null, null, false);
        }
        PromoConfigService.PromoTerms t = terms.get();
        return new PromoTermsDto(promoCode, automationKey, label,
                BigDecimal.valueOf(t.discountCents()).movePointLeft(2),
                t.minSpendCents() == null ? null : BigDecimal.valueOf(t.minSpendCents()).movePointLeft(2),
                t.configured());
    }
}
