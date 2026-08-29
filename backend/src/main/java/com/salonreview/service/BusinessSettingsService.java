package com.salonreview.service;

import com.salonreview.domain.Business;
import com.salonreview.domain.SalonConfig;
import com.salonreview.repo.BusinessRepository;
import com.salonreview.repo.SalonConfigRepository;
import com.salonreview.square.SettlementPreviewService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Backs the owner-facing Business Settings form (Phase 4/6.4): the calling business's own name,
 * timezone, and financial config ({@code salon_config}) in one place. {@code salon_config} may not
 * exist yet for a brand-new business (see V94 — this table only ever had exactly one row before
 * multi-tenancy) — the first PUT creates it, and requires the genuinely load-bearing money fields
 * (owner short name, base commission rate, card tip fee rate) explicitly rather than silently
 * defaulting them to zero.
 */
@Service
public class BusinessSettingsService {

    private final BusinessRepository businesses;
    private final SalonConfigRepository salonConfig;
    private final SettlementPreviewService settlementPreview;

    public BusinessSettingsService(BusinessRepository businesses, SalonConfigRepository salonConfig,
                                   SettlementPreviewService settlementPreview) {
        this.businesses = businesses;
        this.salonConfig = salonConfig;
        this.settlementPreview = settlementPreview;
    }

    public record View(Business business, SalonConfig config) {
        public boolean configured() {
            return config != null;
        }
    }

    public View get(Long businessId) {
        Business business = requireBusiness(businessId);
        return new View(business, salonConfig.findByBusinessId(businessId).orElse(null));
    }

    @Transactional
    public View update(Long businessId, String name, String timezone, String ownerShortName,
                        BigDecimal baseCommissionRate, Boolean tierEnabled, Integer tierServiceThreshold,
                        BigDecimal servicePriceCutoff, BigDecimal cardTipFeeRate, BigDecimal noShowFeeAmount,
                        Boolean restrictDiscountCoverage, String coveredDiscountNames,
                        String googleReviewUrl, String yelpReviewUrl, String feedbackFormUrl) {
        Business business = requireBusiness(businessId);
        if (name != null && !name.isBlank()) business.setName(name.trim());
        if (timezone != null && !timezone.isBlank()) business.setTimezone(timezone.trim());
        // checkout_review_request stays off for this business (see CheckoutReviewTriggerService)
        // until all three are set — null/blank here means "leave unchanged", same convention as
        // name/timezone above, not "clear it back out" (no UI path for that yet, matching
        // coveredDiscountNames' own limitation below).
        if (googleReviewUrl != null && !googleReviewUrl.isBlank()) business.setGoogleReviewUrl(googleReviewUrl.trim());
        if (yelpReviewUrl != null && !yelpReviewUrl.isBlank()) business.setYelpReviewUrl(yelpReviewUrl.trim());
        if (feedbackFormUrl != null && !feedbackFormUrl.isBlank()) business.setFeedbackFormUrl(feedbackFormUrl.trim());
        businesses.save(business);

        Optional<SalonConfig> existing = salonConfig.findByBusinessId(businessId);
        SalonConfig config = existing.orElseGet(() -> SalonConfig.builder().businessId(businessId).build());
        boolean creating = existing.isEmpty();

        if (ownerShortName != null && !ownerShortName.isBlank()) config.setOwnerShortName(ownerShortName.trim());
        else if (creating) throw missingField("ownerShortName");

        if (baseCommissionRate != null) config.setBaseCommissionRate(baseCommissionRate);
        else if (creating) throw missingField("baseCommissionRate");

        // Not yet exposed on the form (no business has needed to edit it this session) — but it's
        // NOT NULL in the DB and only meaningful when tierEnabled, so default it to the base rate on
        // creation the same way tierServiceThreshold/servicePriceCutoff default to inert zeros below.
        if (creating) config.setTierCommissionRate(config.getBaseCommissionRate());

        if (cardTipFeeRate != null) config.setCardTipFeeRate(cardTipFeeRate);
        else if (creating) throw missingField("cardTipFeeRate");

        if (tierEnabled != null) config.setTierEnabled(tierEnabled);
        else if (creating) config.setTierEnabled(false); // opt-in, not opt-out, for a brand-new business

        // Both NOT NULL DB columns but only meaningful when the tier program is on; 0 is a safe,
        // inert default when it's off (never read by TierCommissionEngine in that case).
        if (tierServiceThreshold != null) config.setTierServiceThreshold(tierServiceThreshold);
        else if (creating) config.setTierServiceThreshold(0);

        if (servicePriceCutoff != null) config.setServicePriceCutoff(servicePriceCutoff);
        else if (creating) config.setServicePriceCutoff(BigDecimal.ZERO);

        // Phase 4.4: no explicit "unchanged" default needed on creation — null (the field's own
        // natural default) already means exactly the right thing for a brand-new business: no
        // no-show fee program until the owner explicitly configures one.
        if (noShowFeeAmount != null) config.setNoShowFeeAmount(noShowFeeAmount);

        // Both default to "off" (false/null) on creation, same as the entity's own DB default — no
        // explicit branch needed there, unlike the NOT NULL money fields above.
        if (restrictDiscountCoverage != null) config.setRestrictDiscountCoverage(restrictDiscountCoverage);
        if (coveredDiscountNames != null) config.setCoveredDiscountNames(blankToNull(coveredDiscountNames));

        salonConfig.save(config);
        // Commission config (rate, tier settings, price cutoff, discount coverage) affects every
        // month's settlement equally — busts every cached month for this business, not just one.
        settlementPreview.invalidateCache();
        return new View(business, config);
    }

    private static String blankToNull(String s) {
        return s.isBlank() ? null : s.trim();
    }

    private Business requireBusiness(Long businessId) {
        return businesses.findById(businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such business"));
    }

    private static ResponseStatusException missingField(String field) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,
                field + " is required the first time this business's financial config is set up");
    }
}
