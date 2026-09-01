package com.salonreview.seo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salonreview.config.SeoCredentialCipher;
import com.salonreview.domain.SeoConnection;
import com.salonreview.repo.SeoConnectionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;

/**
 * Backs the owner-facing "Connect SEO monitoring" settings form (Phase 7) — same shape as
 * {@link com.salonreview.square.SquareConnectionService}: paste-in credentials (no OAuth flow in
 * this change, see proposal.md Non-Goals), validated before anything is written, never expose
 * plaintext through any DTO/HTTP response or log line.
 *
 * Validation here is structural only (the service-account JSON parses and has the fields a real
 * Google API call will need) — a live Search Console/GA4 call to actually verify the credentials
 * work requires the API clients built in Phase 3, not yet available to this service.
 */
@Service
public class SeoConnectionService {

    private final SeoConnectionRepository repo;
    private final SeoCredentialCipher cipher;
    // Constructed directly rather than @Autowired: this app's Spring context doesn't register a
    // default ObjectMapper bean the way a vanilla Spring Boot web app does (confirmed via a real
    // CI failure — NoSuchBeanDefinitionException for ObjectMapper — while wiring this up), and
    // structural JSON-tree parsing here needs no custom Jackson configuration anyway.
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SeoConnectionService(SeoConnectionRepository repo, SeoCredentialCipher cipher) {
        this.repo = repo;
        this.cipher = cipher;
    }

    public Optional<SeoConnection> get(Long businessId) {
        return repo.findByBusinessId(businessId);
    }

    /** Decrypted service-account JSON — for the Phase 3 Google API clients only. Never expose the
     * plaintext through any owner-facing DTO/HTTP response or log line. */
    public String decryptedServiceAccountJson(SeoConnection connection) {
        return cipher.decrypt(connection.getGscServiceAccountJsonEncrypted());
    }

    public String decryptedPagespeedApiKey(SeoConnection connection) {
        return cipher.decrypt(connection.getPagespeedApiKeyEncrypted());
    }

    /** The service-account's {@code client_email} — a meaningful, human-readable identifier to
     * show in the settings UI (unlike a masked substring of a JSON blob, which would show nothing
     * useful). {@code null} if nothing connected yet. */
    public String serviceAccountEmail(SeoConnection connection) {
        if (connection == null) return null;
        JsonNode parsed = parseServiceAccountJson(decryptedServiceAccountJson(connection));
        JsonNode email = parsed.get("client_email");
        return email == null ? null : email.asText();
    }

    /** Same last-4 masking convention as {@code SquareConnectionService.maskedAccessToken}. */
    public String maskedPagespeedApiKey(SeoConnection connection) {
        if (connection == null) return null;
        String key = decryptedPagespeedApiKey(connection);
        return key.length() <= 4 ? "••••" : "••••" + key.substring(key.length() - 4);
    }

    /**
     * {@code gscServiceAccountJson}/{@code pagespeedApiKey} {@code null}/blank keeps the existing
     * encrypted value unchanged (same convention as {@code SquareConnectionService.connect}'s
     * {@code accessToken}) — required the first time a business connects. Validates the
     * service-account JSON actually parses and has {@code client_email}/{@code private_key} before
     * saving anything, so a pasted-in mistake fails loudly here, not silently on the first sync
     * job run.
     */
    @Transactional
    public SeoConnection connect(Long businessId, String gscServiceAccountJson, String ga4PropertyId,
                                  String ga4MeasurementId, String pagespeedApiKey, Long connectedByUserId) {
        SeoConnection existing = repo.findByBusinessId(businessId).orElse(null);

        boolean serviceAccountProvided = gscServiceAccountJson != null && !gscServiceAccountJson.isBlank();
        if (!serviceAccountProvided && existing == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "gscServiceAccountJson is required to connect SEO monitoring for the first time");
        }
        if (serviceAccountProvided) {
            validateServiceAccountJson(gscServiceAccountJson);
        }

        boolean pagespeedKeyProvided = pagespeedApiKey != null && !pagespeedApiKey.isBlank();
        if (!pagespeedKeyProvided && existing == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "pagespeedApiKey is required to connect SEO monitoring for the first time");
        }

        if (ga4PropertyId == null || ga4PropertyId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ga4PropertyId is required");
        }
        if (ga4MeasurementId == null || ga4MeasurementId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ga4MeasurementId is required");
        }

        SeoConnection connection = existing != null ? existing : new SeoConnection();
        connection.setBusinessId(businessId);
        if (serviceAccountProvided) {
            connection.setGscServiceAccountJsonEncrypted(cipher.encrypt(gscServiceAccountJson));
        }
        if (pagespeedKeyProvided) {
            connection.setPagespeedApiKeyEncrypted(cipher.encrypt(pagespeedApiKey));
        }
        connection.setGa4PropertyId(ga4PropertyId.trim());
        connection.setGa4MeasurementId(ga4MeasurementId.trim());
        connection.setConnectedByUserId(connectedByUserId);
        if (connection.getConnectedAt() == null) {
            connection.setConnectedAt(Instant.now());
        }
        return repo.save(connection);
    }

    private JsonNode parseServiceAccountJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("Stored SEO service-account JSON is no longer valid JSON", e);
        }
    }

    private void validateServiceAccountJson(String json) {
        JsonNode parsed;
        try {
            parsed = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "gscServiceAccountJson is not valid JSON: " + e.getMessage());
        }
        if (parsed.get("client_email") == null || parsed.get("private_key") == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "gscServiceAccountJson is missing client_email/private_key — paste the full JSON "
                            + "key file downloaded from Google Cloud, not a partial copy");
        }
    }
}
