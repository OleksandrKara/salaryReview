package com.salonreview.sms;

import com.salonreview.config.RebookingProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** HMAC signer for the checkout-review-request satisfaction email's five rating links — same
 * algorithm/shape as {@code RebookingPromoSignerTest}, deliberately reusing that secret (see this
 * class's own doc for why). */
class CheckoutReviewRatingSignerTest {

    private static RebookingProperties configuredProperties() {
        RebookingProperties properties = new RebookingProperties();
        properties.setPromoSecret("test-secret");
        return properties;
    }

    @Test
    @DisplayName("no secret configured → sign returns null, never an unsigned link")
    void unconfiguredSecretRefusesToSign() {
        CheckoutReviewRatingSigner signer = new CheckoutReviewRatingSigner(new RebookingProperties());

        assertThat(signer.sign(1L, 5, 9999999999L)).isNull();
    }

    @Test
    @DisplayName("no secret configured → verify is always false, never accidentally open")
    void unconfiguredSecretFailsClosedOnVerify() {
        CheckoutReviewRatingSigner signer = new CheckoutReviewRatingSigner(new RebookingProperties());

        assertThat(signer.verify(1L, 5, 9999999999L, "anything")).isFalse();
    }

    @Test
    @DisplayName("a signature verifies against the exact (flowId, rating, exp) it was signed for")
    void signatureVerifiesForExactInputs() {
        CheckoutReviewRatingSigner signer = new CheckoutReviewRatingSigner(configuredProperties());

        String signature = signer.sign(42L, 5, 9999999999L);

        assertThat(signature).isNotNull();
        assertThat(signer.verify(42L, 5, 9999999999L, signature)).isTrue();
    }

    @Test
    @DisplayName("a different flowId, rating, or exp than what was signed all fail verification")
    void signatureFailsForAnyChangedInput() {
        CheckoutReviewRatingSigner signer = new CheckoutReviewRatingSigner(configuredProperties());
        String signature = signer.sign(42L, 5, 9999999999L);

        assertThat(signer.verify(99L, 5, 9999999999L, signature)).isFalse(); // different flow
        assertThat(signer.verify(42L, 1, 9999999999L, signature)).isFalse(); // different rating
        assertThat(signer.verify(42L, 5, 8888888888L, signature)).isFalse(); // different exp
        assertThat(signer.verify(42L, 5, 9999999999L, "tampered")).isFalse(); // tampered signature
    }

    @Test
    @DisplayName("a rating link's own signature never verifies for a different rating value — a "
            + "tampered '?rating=5' can't be swapped onto a '?rating=1' link's signature")
    void ratingIsPartOfWhatsSigned() {
        CheckoutReviewRatingSigner signer = new CheckoutReviewRatingSigner(configuredProperties());
        String sigForOne = signer.sign(42L, 1, 9999999999L);

        assertThat(signer.verify(42L, 5, 9999999999L, sigForOne)).isFalse();
    }
}
