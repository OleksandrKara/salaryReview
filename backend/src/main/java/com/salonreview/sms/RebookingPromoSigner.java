package com.salonreview.sms;

import com.salonreview.config.RebookingProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * HMAC-SHA256 signer for the same-day-rebooking promo link's {@code promo}/{@code exp} query
 * params — see openspec/changes/same-day-rebooking-discount design.md D8. Deterministic: the same
 * (code, epoch) pair under the same secret always produces the same signature, so nothing extra
 * needs to be persisted to reconstruct it at short-link-resolve time. akluxnails-home must compute
 * this identically (same algorithm, same input shape, same shared secret) to verify it.
 */
@Component
public class RebookingPromoSigner {

    private static final String ALGORITHM = "HmacSHA256";

    private final RebookingProperties properties;

    public RebookingPromoSigner(RebookingProperties properties) {
        this.properties = properties;
    }

    /** {@code null} if no secret is configured — callers must treat this as "cannot sign," not
     * silently produce an unsigned/insecure link. */
    public String sign(String promoCode, long expEpochSeconds) {
        if (!properties.isSigningConfigured()) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(properties.getPromoSecret().getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] raw = mac.doFinal((promoCode + "." + expEpochSeconds).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign rebooking promo link", e);
        }
    }

    /** Constant-time comparison against a freshly-computed signature — never a plain {@code equals}.
     * {@code false} whenever signing itself isn't possible (no secret configured) or either input
     * is missing, matching this codebase's "fail closed" convention elsewhere (webhook signature
     * checks, marketing-consent lookups). */
    public boolean verify(String promoCode, long expEpochSeconds, String candidateSignature) {
        String expected = sign(promoCode, expEpochSeconds);
        if (expected == null || candidateSignature == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), candidateSignature.getBytes(StandardCharsets.UTF_8));
    }
}
