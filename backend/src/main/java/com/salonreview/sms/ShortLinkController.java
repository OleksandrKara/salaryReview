package com.salonreview.sms;

import com.salonreview.config.MarketingLandingProperties;
import com.salonreview.domain.SmsMessage;
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

    /** Plain redirect to the home landing page, no promo params — for {@code repeat_customer_winback},
     * which carries no discount (see V72). Distinct from {@link #REBOOK_PREFIX}/{@link #WINBACK_PREFIX}
     * because there's no signature or expiry to verify; it's just a click-tracked booking link. */
    static final String BOOK_NOW_TARGET = "BOOK_NOW";

    private final SmsMessageRepository repository;
    private final RebookingPromoSigner promoSigner;
    private final MarketingLandingProperties landingProperties;

    public ShortLinkController(SmsMessageRepository repository, RebookingPromoSigner promoSigner,
                                MarketingLandingProperties landingProperties) {
        this.repository = repository;
        this.promoSigner = promoSigner;
        this.landingProperties = landingProperties;
    }

    @GetMapping("/r/{token}")
    public ResponseEntity<Void> redirect(@PathVariable String token) {
        SmsMessage message = repository.findByClickToken(token).orElse(null);
        String target = message == null ? null : resolveTarget(message.getLinkTarget());
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
     * {@code WINBACK:<epochSeconds>}). */
    private String resolveTarget(String linkTarget) {
        if (linkTarget != null && linkTarget.startsWith(REBOOK_PREFIX)) {
            return resolveRebookingPromo(REBOOK_PROMO_CODE, linkTarget.substring(REBOOK_PREFIX.length()));
        }
        if (linkTarget != null && linkTarget.startsWith(WINBACK_PREFIX)) {
            return resolveRebookingPromo(WINBACK_PROMO_CODE, linkTarget.substring(WINBACK_PREFIX.length()));
        }
        if (BOOK_NOW_TARGET.equals(linkTarget)) {
            return landingProperties.baseUrlFor("home");
        }
        return CheckoutReviewLinks.resolve(linkTarget);
    }

    /** Builds the signed promo URL on demand — the signature is never stored, only the expiry
     * epoch (see class doc and RebookingPromoSigner). If signing isn't configured (no secret set
     * yet), there's no safe way to produce a valid link, so this resolves to {@code null} (404)
     * rather than an unsigned/forgeable one. {@code promoCode} distinguishes which promo this is
     * (REBOOK10 or WINBACK5) — {@link RebookingPromoSigner} is already generic over it. */
    private String resolveRebookingPromo(String promoCode, String epochSecondsRaw) {
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
