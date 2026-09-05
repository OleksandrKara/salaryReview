package com.salonreview.sms;

import com.salonreview.config.RebookingProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * HMAC-SHA256 signer for the checkout-review-request email's five rating links (one per 1-5
 * star option) — same algorithm/shape as {@link RebookingPromoSigner}, deliberately reusing its
 * {@link RebookingProperties#getPromoSecret()} rather than adding a second dedicated secret to
 * configure: an HMAC secret is safe to reuse across purposes as long as what's signed is
 * namespaced (the {@code CHECKOUTREVIEW.} prefix below), and this avoids a new required env var
 * for a feature that's otherwise pure application code. Deterministic — nothing needs to be
 * persisted to verify a link later, same reasoning as {@link RebookingPromoSigner}.
 */
@Component
public class CheckoutReviewRatingSigner {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String NAMESPACE = "CHECKOUTREVIEW.";

    private final RebookingProperties properties;

    public CheckoutReviewRatingSigner(RebookingProperties properties) {
        this.properties = properties;
    }

    /** {@code null} if no secret is configured — callers must treat this as "cannot sign," not
     * silently produce an unsigned/insecure link. */
    public String sign(long flowId, int rating, long expEpochSeconds) {
        if (!properties.isSigningConfigured()) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(properties.getPromoSecret().getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] raw = mac.doFinal((NAMESPACE + flowId + "." + rating + "." + expEpochSeconds)
                    .getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign checkout-review rating link", e);
        }
    }

    /** Constant-time comparison against a freshly-computed signature — never a plain {@code equals}.
     * {@code false} whenever signing itself isn't possible (no secret configured) or either input
     * is missing, matching {@link RebookingPromoSigner#verify}'s fail-closed convention. */
    public boolean verify(long flowId, int rating, long expEpochSeconds, String candidateSignature) {
        String expected = sign(flowId, rating, expEpochSeconds);
        if (expected == null || candidateSignature == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), candidateSignature.getBytes(StandardCharsets.UTF_8));
    }
}
