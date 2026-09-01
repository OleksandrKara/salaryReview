package com.salonreview.seo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;

/**
 * Exchanges a Google service-account JSON key for a short-lived OAuth access token, using the
 * standard JWT-bearer flow (RFC 7523) — hand-rolled RS256 signing via {@code java.security}
 * rather than pulling in Google's own client libraries (google-auth-library, google-api-client),
 * matching this codebase's existing preference for thin custom HTTP clients over heavyweight SDKs
 * (see {@code SquareClient}'s own doc comment, and {@code SeoCredentialCipher}/
 * {@code SquareCredentialCipher} hand-rolling AES-GCM the same way instead of adding a crypto
 * library for it).
 *
 * <p>One instance per Google API call site that needs it ({@code SearchConsoleClient},
 * {@code GoogleAnalyticsClient}) — each holds its own cached access token (Google tokens last ~1
 * hour; refreshed 60s before expiry) so a scheduled job making several calls in the same run
 * doesn't re-sign a JWT for every request.
 */
class GoogleServiceAccountAuth {

    private static final String TOKEN_URI = "https://oauth2.googleapis.com/token";
    private static final long REFRESH_SKEW_SECONDS = 60;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestClient http;
    private final String clientEmail;
    private final PrivateKey privateKey;
    private final String scope;

    private volatile String cachedToken;
    private volatile Instant cachedTokenExpiresAt = Instant.EPOCH;

    /** @param serviceAccountJson the full JSON key file content (decrypted plaintext — caller's
     *                            responsibility to never log it)
     * @param scope space-separated OAuth scopes, e.g. {@code "https://www.googleapis.com/auth/webmasters.readonly"} */
    GoogleServiceAccountAuth(String serviceAccountJson, String scope) {
        this(serviceAccountJson, scope, GoogleRestClients.builder("https://oauth2.googleapis.com").build());
    }

    /** Test-only constructor — points this client at an arbitrary {@link RestClient} (e.g. one
     * bound to {@code MockRestServiceServer}) instead of building one for the real Google token
     * endpoint, same convention as {@code SquareClient}'s package-private test constructor. */
    GoogleServiceAccountAuth(String serviceAccountJson, String scope, RestClient http) {
        this.scope = scope;
        this.http = http;
        JsonNode parsed;
        try {
            parsed = objectMapper.readTree(serviceAccountJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("Service-account JSON is not valid JSON", e);
        }
        this.clientEmail = requireField(parsed, "client_email");
        this.privateKey = parsePrivateKey(requireField(parsed, "private_key"));
    }

    /** Returns a currently-valid access token, refreshing it first if it's expired or about to. */
    synchronized String accessToken() {
        if (cachedToken != null && Instant.now().isBefore(cachedTokenExpiresAt.minusSeconds(REFRESH_SKEW_SECONDS))) {
            return cachedToken;
        }
        String jwt = signAssertion();
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
        form.add("assertion", jwt);

        JsonNode response = http.post()
                .uri(TOKEN_URI)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(JsonNode.class);

        cachedToken = requireField(response, "access_token");
        long expiresInSeconds = response.path("expires_in").asLong(3600);
        cachedTokenExpiresAt = Instant.now().plusSeconds(expiresInSeconds);
        return cachedToken;
    }

    private String signAssertion() {
        long now = Instant.now().getEpochSecond();
        String header = base64Url("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
        String claims = base64Url(String.format(
                "{\"iss\":\"%s\",\"scope\":\"%s\",\"aud\":\"%s\",\"iat\":%d,\"exp\":%d}",
                clientEmail, scope, TOKEN_URI, now, now + 3600));
        String signingInput = header + "." + claims;
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
            String signedPart = base64UrlBytes(signature.sign());
            return signingInput + "." + signedPart;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign Google service-account JWT", e);
        }
    }

    private static PrivateKey parsePrivateKey(String pem) {
        try {
            String cleaned = pem
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(cleaned);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            return keyFactory.generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalArgumentException("Service-account private_key is not a valid PKCS8 RSA key", e);
        }
    }

    private static String requireField(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.asText().isBlank()) {
            throw new IllegalArgumentException("Service-account JSON/response is missing required field: " + field);
        }
        return value.asText();
    }

    private static String base64Url(String plain) {
        return base64UrlBytes(plain.getBytes(StandardCharsets.UTF_8));
    }

    private static String base64UrlBytes(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
