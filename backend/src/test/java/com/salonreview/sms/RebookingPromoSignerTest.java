package com.salonreview.sms;

import com.salonreview.config.RebookingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * See openspec/changes/same-day-rebooking-discount design.md D8 — the property this signer must
 * hold is that {@code exp} can't be forged by editing the URL, since verification always
 * recomputes the signature server-side rather than trusting the caller.
 */
class RebookingPromoSignerTest {

    private RebookingProperties properties;
    private RebookingPromoSigner signer;

    @BeforeEach
    void setUp() {
        properties = new RebookingProperties();
        properties.setPromoSecret("test-secret-value");
        signer = new RebookingPromoSigner(properties);
    }

    @Test
    @DisplayName("no secret configured → cannot sign, returns null")
    void noSecretCannotSign() {
        properties.setPromoSecret("");
        assertThat(signer.sign("REBOOK10", 1000L)).isNull();
    }

    @Test
    @DisplayName("same inputs → same signature (deterministic)")
    void deterministicForSameInputs() {
        String a = signer.sign("REBOOK10", 1700000000L);
        String b = signer.sign("REBOOK10", 1700000000L);
        assertThat(a).isNotNull().isEqualTo(b);
    }

    @Test
    @DisplayName("different exp → different signature")
    void differentExpDiffersSignature() {
        String a = signer.sign("REBOOK10", 1700000000L);
        String b = signer.sign("REBOOK10", 1700000001L);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("different secret → different signature (can't be forged without it)")
    void differentSecretDiffersSignature() {
        String a = signer.sign("REBOOK10", 1700000000L);
        properties.setPromoSecret("a-different-secret");
        String b = signer.sign("REBOOK10", 1700000000L);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("verify: correct signature passes")
    void verifyCorrectSignaturePasses() {
        String sig = signer.sign("REBOOK10", 1700000000L);
        assertThat(signer.verify("REBOOK10", 1700000000L, sig)).isTrue();
    }

    @Test
    @DisplayName("verify: tampered exp fails — this is the anti-forgery property")
    void verifyTamperedExpFails() {
        String sig = signer.sign("REBOOK10", 1700000000L);
        assertThat(signer.verify("REBOOK10", 1700099999L, sig)).isFalse();
    }

    @Test
    @DisplayName("verify: missing signature fails")
    void verifyMissingSignatureFails() {
        assertThat(signer.verify("REBOOK10", 1700000000L, null)).isFalse();
    }

    @Test
    @DisplayName("verify: no secret configured fails closed, never passes")
    void verifyNoSecretFailsClosed() {
        String sig = signer.sign("REBOOK10", 1700000000L);
        properties.setPromoSecret("");
        assertThat(signer.verify("REBOOK10", 1700000000L, sig)).isFalse();
    }
}
