package com.salonreview.sms;

import com.salonreview.config.MarketingLandingProperties;
import com.salonreview.domain.Business;
import com.salonreview.domain.SmsMessage;
import com.salonreview.repo.BusinessRepository;
import com.salonreview.repo.SmsMessageRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.net.URI;

/**
 * Click-tracked short link for the checkout-review-request automation's two reply branches, and
 * for the same-day-rebooking-discount and lapsed-customer-winback automations' signed promo links
 * — see openspec/changes/sms-automations-hub design.md D6,
 * openspec/changes/same-day-rebooking-discount design.md D5/D8/D9, and
 * openspec/changes/lapsed-customer-winback-automation design.md D9. {@code permitAll()} in
 * {@link com.salonreview.config.SecurityConfig} — nothing sensitive here, just a redirect with a
 * click timestamp.
 */
@RestController
public class ShortLinkController {

    /** {@code link_target} shape for the same-day-rebooking promo: {@code REBOOK:<epochSeconds>}.
     * The signature is never stored — it's deterministic from the epoch and the shared secret, so
     * it's recomputed here at resolve time (see {@link RebookingPromoSigner}). */
    private static final String REBOOK_PREFIX = "REBOOK:";
    private static final String REBOOK_PROMO_CODE = "REBOOK10";

    /** Same shape, same reasoning, for the lapsed-customer-winback $5 coupon — a distinct prefix/
     * code so the two promos never collide, see design.md D9. {@link RebookingPromoSigner} needed
     * no changes to support this — it was already generic over the promo code. */
    private static final String WINBACK_PREFIX = "WINBACK:";
    private static final String WINBACK_PROMO_CODE = "WINBACK5";

    /** Plain redirect to the home landing page, no promo params. {@code repeat_customer_winback}
     * sent this before it started reusing {@link #WINBACK_PREFIX} for its own $5 coupon (see V72,
     * V78/V79) — kept only so any surviving historical {@code sms_message} row with this exact
     * link_target (if one somehow wasn't caught by the V79 backfill) still resolves to something
     * sane instead of a dead link. Distinct from {@link #REBOOK_PREFIX}/{@link #WINBACK_PREFIX}
     * because there's no signature or expiry to verify; it's just a click-tracked booking link. */
    static final String BOOK_NOW_TARGET = "BOOK_NOW";

    private final SmsMessageRepository repository;
    private final BusinessRepository businessRepository;
    private final RebookingPromoSigner promoSigner;
    private final MarketingLandingProperties landingProperties;
    private final PromoConfigService promoConfigService;

    public ShortLinkController(SmsMessageRepository repository, BusinessRepository businessRepository,
                                RebookingPromoSigner promoSigner, MarketingLandingProperties landingProperties,
                                PromoConfigService promoConfigService) {
        this.repository = repository;
        this.businessRepository = businessRepository;
        this.promoSigner = promoSigner;
        this.landingProperties = landingProperties;
        this.promoConfigService = promoConfigService;
    }

    @GetMapping("/r/{token}")
    public ResponseEntity<Void> redirect(@PathVariable String token) {
        SmsMessage message = repository.findByClickToken(token).orElse(null);
        Business business = message == null ? null : businessRepository.findById(message.getBusinessId()).orElse(null);
        String target = message == null ? null : resolveTarget(message.getLinkTarget(), business);
        if (target == null) {
            return ResponseEntity.notFound().build();
        }
        if (message.getClickedAt() == null) {
            message.setClickedAt(Instant.now());
            repository.save(message);
        }
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(target))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }

    /** {@code null} if {@code linkTarget} isn't a recognized shape (a fixed
     * {@code CheckoutReviewLinks} target, {@code REBOOK:<epochSeconds>}, or
     * {@code WINBACK:<epochSeconds>}), or if {@code business} can't be resolved at all. */
    private String resolveTarget(String linkTarget, Business business) {
        if (business == null) {
            return null;
        }
        if (linkTarget != null && linkTarget.startsWith(REBOOK_PREFIX)) {
            return resolveRebookingPromo(REBOOK_PROMO_CODE, linkTarget.substring(REBOOK_PREFIX.length()), business);
        }
        if (linkTarget != null && linkTarget.startsWith(WINBACK_PREFIX)) {
            return resolveRebookingPromo(WINBACK_PROMO_CODE, linkTarget.substring(WINBACK_PREFIX.length()), business);
        }
        if (BOOK_NOW_TARGET.equals(linkTarget)) {
            return publicSiteFor(business);
        }
        return CheckoutReviewLinks.resolve(linkTarget, business);
    }

    /** This business's own public landing page — falls back to the legacy "home" landing config
     * only for a business that hasn't been set up with one yet (see {@link Business#getPublicDomain()}),
     * rather than ever pointing a click-tracked link at a domain that isn't this business's own. */
    private String publicSiteFor(Business business) {
        String domain = business.getPublicDomain();
        if (domain != null && !domain.isBlank()) {
            return "https://" + domain;
        }
        return landingProperties.baseUrlFor("home");
    }

    /** Builds the signed promo URL on demand — the signature is never stored, only the expiry
     * epoch (see class doc and RebookingPromoSigner). {@code null} (404) if signing isn't
     * configured, or if this business has no {@link PromoConfigService} terms for
     * {@code promoCode} yet (no Square Customer Group/Discount/Pricing Rule exists to actually
     * apply) — never a live-looking coupon link that silently sends a customer to a page with
     * nothing to redeem. {@code promoCode} distinguishes which promo this is (REBOOK10 or
     * WINBACK5) — {@link RebookingPromoSigner} is already generic over it. */
    private String resolveRebookingPromo(String promoCode, String epochSecondsRaw, Business business) {
        if (promoConfigService.get(business.getId(), promoCode).isEmpty()) {
            return null;
        }
        long expEpochSeconds;
        try {
            expEpochSeconds = Long.parseLong(epochSecondsRaw);
        } catch (NumberFormatException e) {
            return null;
        }
        String signature = promoSigner.sign(promoCode, expEpochSeconds);
        if (signature == null) {
            return null;
        }
        return promoRedemptionBaseUrl(business) + "/?promo=" + promoCode + "&exp=" + expEpochSeconds + "&sig=" + signature;
    }

    /** Business A's promo redemption UI lives on the legacy "home" landing page
     * (akluxnails-home — see {@link MarketingLandingProperties}), a different domain than its own
     * {@link Business#getPublicDomain()} (mani.akluxnails.com). Every other business's redemption
     * UI lives on its own public domain instead — see openspec/changes for the landing-page side
     * of this feature. */
    private String promoRedemptionBaseUrl(Business business) {
        if (businessRepository.legacySmsBusiness().getId().equals(business.getId())) {
            return landingProperties.baseUrlFor("home");
        }
        return publicSiteFor(business);
    }
}
