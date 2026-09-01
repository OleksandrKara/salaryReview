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
 * AES-256-GCM encryption for {@code seo_connection}'s two secret columns
 * ({@code gsc_service_account_json_encrypted}, {@code pagespeed_api_key_encrypted}) —
 * seo-monitoring-dashboard design.md D1. Deliberately a separate class and master key
 * ({@code SEO_CREDENTIALS_MASTER_KEY}) from {@link SquareCredentialCipher}'s
 * ({@code SQUARE_CREDENTIALS_MASTER_KEY}) — rotating one credential type's key must never force
 * re-encrypting the other's. Same AES/GCM/NoPadding shape and ciphertext layout
 * (base64(12-byte random IV || GCM output)) as {@link SquareCredentialCipher} — intentionally
 * duplicated rather than shared, see that class's own doc comment for why a generic shared cipher
 * was rejected.
 */
@Component
public class SeoCredentialCipher {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    private final SecureRandom random = new SecureRandom();
    private final byte[] masterKey;

    public SeoCredentialCipher(@Value("${seo.credentials.master-key:}") String masterKeyBase64) {
        this.masterKey = (masterKeyBase64 == null || masterKeyBase64.isBlank())
                ? null
                : Base64.getDecoder().decode(masterKeyBase64);
    }

    /** True once a master key has actually been supplied — callers that need to encrypt/decrypt
     * should check this before attempting to, rather than let a blank key crash app startup for
     * every deploy that hasn't set {@code SEO_CREDENTIALS_MASTER_KEY} yet. */
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
            throw new IllegalStateException("Failed to encrypt SEO credential", e);
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
            throw new IllegalStateException("Failed to decrypt SEO credential", e);
        }
    }

    private void requireConfigured() {
        if (masterKey == null) {
            throw new IllegalStateException("SEO_CREDENTIALS_MASTER_KEY is not set — cannot "
                    + "encrypt/decrypt SEO credentials");
        }
    }
}
