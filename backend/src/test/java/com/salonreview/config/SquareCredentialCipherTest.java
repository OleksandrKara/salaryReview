package com.salonreview.config;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SquareCredentialCipherTest {

    private static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    @Test
    void roundTripsAnAccessToken() {
        SquareCredentialCipher cipher = new SquareCredentialCipher(randomKey());
        String token = "sq0atp-real-looking-access-token-value";

        String encrypted = cipher.encrypt(token);

        assertThat(encrypted).isNotEqualTo(token);
        assertThat(cipher.decrypt(encrypted)).isEqualTo(token);
    }

    @Test
    void twoEncryptionsOfTheSamePlaintextDiffer() {
        // Confirms a fresh random IV is actually used each call, not a fixed/zero one.
        SquareCredentialCipher cipher = new SquareCredentialCipher(randomKey());

        assertThat(cipher.encrypt("same-token")).isNotEqualTo(cipher.encrypt("same-token"));
    }

    @Test
    void decryptingWithADifferentKeyFails() {
        SquareCredentialCipher writer = new SquareCredentialCipher(randomKey());
        SquareCredentialCipher reader = new SquareCredentialCipher(randomKey());
        String encrypted = writer.encrypt("token");

        assertThatThrownBy(() -> reader.decrypt(encrypted)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void isConfiguredIsFalseWithoutAMasterKey() {
        assertThat(new SquareCredentialCipher("").isConfigured()).isFalse();
        assertThat(new SquareCredentialCipher(null).isConfigured()).isFalse();
        assertThat(new SquareCredentialCipher(randomKey()).isConfigured()).isTrue();
    }

    @Test
    void encryptWithoutAMasterKeyFailsLoudly() {
        SquareCredentialCipher cipher = new SquareCredentialCipher("");

        assertThatThrownBy(() -> cipher.encrypt("token")).isInstanceOf(IllegalStateException.class);
    }
}
