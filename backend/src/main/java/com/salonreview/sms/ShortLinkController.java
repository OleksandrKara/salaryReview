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

    /** The signed REBOOK10/WINBACK5 promo links only resolve to something that actually applies a
     * discount for Business A ({@code akluxnails}) — {@code RebookingProperties}' Square customer-
     * group ids and {@code InternalNotificationController}'s enrollment endpoint are both
     * hardcoded to that one business/Square account today (see their own doc comments), and no
     * other business's landing page has any promo-redemption code at all yet. A second business
     * with these two automations enabled would otherwise generate a live-looking coupon link that
     * silently sends its own customers to Business A's website instead of failing loudly — 404 is
     * safer than that. Revisit once a business other than Business A gets its own Square
     * Catalog/CustomerGroup setup and landing-page redemption flow built. */
    private static final String PROMO_REDEMPTION_BUSINESS_SHORT_CODE = "akluxnails";

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

    public ShortLinkController(SmsMessageRepository repository, BusinessRepository businessRepository,
                                RebookingPromoSigner promoSigner, MarketingLandingProperties landingProperties) {
        this.repository = repository;
        this.businessRepository = businessRepository;
        this.promoSigner = promoSigner;
        this.landingProperties = landingProperties;
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
     * configured, or if this isn't {@link #PROMO_REDEMPTION_BUSINESS_SHORT_CODE} — see that
     * constant's own doc for why a coupon link is scoped to one business today. {@code promoCode}
     * distinguishes which promo this is (REBOOK10 or WINBACK5) — {@link RebookingPromoSigner} is
     * already generic over it. */
    private String resolveRebookingPromo(String promoCode, String epochSecondsRaw, Business business) {
        if (!PROMO_REDEMPTION_BUSINESS_SHORT_CODE.equals(business.getShortCode())) {
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
        String homeBaseUrl = landingProperties.baseUrlFor("home");
        return homeBaseUrl + "/?promo=" + promoCode + "&exp=" + expEpochSeconds + "&sig=" + signature;
    }
}
