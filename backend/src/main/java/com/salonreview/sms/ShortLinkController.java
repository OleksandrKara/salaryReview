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
 * for the same-day-rebooking-discount automation's signed promo link — see
 * openspec/changes/sms-automations-hub design.md D6 and
 * openspec/changes/same-day-rebooking-discount design.md D5/D8/D9. {@code permitAll()} in
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
     * {@code CheckoutReviewLinks} target, or {@code REBOOK:<epochSeconds>}). */
    private String resolveTarget(String linkTarget) {
        if (linkTarget != null && linkTarget.startsWith(REBOOK_PREFIX)) {
            return resolveRebookingPromo(linkTarget.substring(REBOOK_PREFIX.length()));
        }
        return CheckoutReviewLinks.resolve(linkTarget);
    }

    /** Builds the signed promo URL on demand — the signature is never stored, only the expiry
     * epoch (see class doc and RebookingPromoSigner). If signing isn't configured (no secret set
     * yet), there's no safe way to produce a valid link, so this resolves to {@code null} (404)
     * rather than an unsigned/forgeable one. */
    private String resolveRebookingPromo(String epochSecondsRaw) {
        long expEpochSeconds;
        try {
            expEpochSeconds = Long.parseLong(epochSecondsRaw);
        } catch (NumberFormatException e) {
            return null;
        }
        String signature = promoSigner.sign(REBOOK_PROMO_CODE, expEpochSeconds);
        if (signature == null) {
            return null;
        }
        String homeBaseUrl = landingProperties.baseUrlFor("home");
        return homeBaseUrl + "/?promo=" + REBOOK_PROMO_CODE + "&exp=" + expEpochSeconds + "&sig=" + signature;
    }
}
