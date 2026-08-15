package com.salonreview.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption for {@code square_connection.access_token_encrypted} — design.md D5. The
 * master key is deployment-wide infra (one key encrypts every business's Square token), so it comes
 * from {@code SQUARE_CREDENTIALS_MASTER_KEY} (base64, 32 raw bytes — generate with
 * {@code openssl rand -base64 32}), not the database. Ciphertext layout is
 * {@code base64(12-byte random IV || GCM output)} — GCM's authentication tag is part of its own
 * output, so no separate MAC is needed.
 */
@Component
public class SquareCredentialCipher {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    private final SecureRandom random = new SecureRandom();
    private final byte[] masterKey;

    public SquareCredentialCipher(@Value("${square.credentials.master-key:}") String masterKeyBase64) {
        this.masterKey = (masterKeyBase64 == null || masterKeyBase64.isBlank())
                ? null
                : Base64.getDecoder().decode(masterKeyBase64);
    }

    /** True once a master key has actually been supplied — callers that need to encrypt/decrypt
     * should check this before attempting to, rather than let a blank key crash app startup for
     * every deploy that hasn't set {@code SQUARE_CREDENTIALS_MASTER_KEY} yet. */
    public boolean isConfigured() {
        return masterKey != null;
    }

    public String encrypt(String plaintext) {
        requireConfigured();
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(masterKey, "AES"),
                    new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt Square credential", e);
        }
    }

    public String decrypt(String encoded) {
        requireConfigured();
        try {
            byte[] combined = Base64.getDecoder().decode(encoded);
            byte[] iv = new byte[IV_LENGTH_BYTES];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH_BYTES);
            byte[] ciphertext = new byte[combined.length - IV_LENGTH_BYTES];
            System.arraycopy(combined, IV_LENGTH_BYTES, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(masterKey, "AES"),
                    new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt Square credential", e);
        }
    }

    private void requireConfigured() {
        if (masterKey == null) {
            throw new IllegalStateException("SQUARE_CREDENTIALS_MASTER_KEY is not set — cannot "
                    + "encrypt/decrypt Square credentials");
        }
    }
}
