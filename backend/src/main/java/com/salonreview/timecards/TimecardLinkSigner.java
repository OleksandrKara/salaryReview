package com.salonreview.timecards;

import com.salonreview.config.RebookingProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Optional;

/** HMAC link for the one-tap "confirm yesterday" page. Token: businessId.providerId.date.exp.sig */
@Component
public class TimecardLinkSigner {

    public record Claim(long businessId, long providerId, LocalDate date) {}

    private final RebookingProperties properties;

    public TimecardLinkSigner(RebookingProperties properties) {
        this.properties = properties;
    }

    /** null when no signing secret is configured. */
    public String token(long businessId, long providerId, LocalDate date, long expEpochSeconds) {
        String payload = businessId + "." + providerId + "." + date + "." + expEpochSeconds;
        String sig = sig(payload);
        return sig == null ? null : payload + "." + sig;
    }

    public Optional<Claim> verify(String token, long nowEpochSeconds) {
        if (token == null) return Optional.empty();
        String[] p = token.split("\\.");
        if (p.length != 5) return Optional.empty();
        try {
            String payload = p[0] + "." + p[1] + "." + p[2] + "." + p[3];
            String expected = sig(payload);
            if (expected == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    p[4].getBytes(StandardCharsets.UTF_8))) return Optional.empty();
            if (Long.parseLong(p[3]) < nowEpochSeconds) return Optional.empty();
            return Optional.of(new Claim(Long.parseLong(p[0]), Long.parseLong(p[1]), LocalDate.parse(p[2])));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private String sig(String payload) {
        if (!properties.isSigningConfigured()) return null;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getPromoSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(("TIMECARD." + payload).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign timecard link", e);
        }
    }
}
