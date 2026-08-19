package com.salonreview.web;

import com.salonreview.config.InternalApiProperties;
import com.salonreview.domain.Business;
import com.salonreview.repo.BusinessRepository;
import com.salonreview.square.SquareConnectionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/**
 * Service-to-service endpoints for salonLandings (mani/AK PMU landing pages) to resolve which
 * business a request belongs to and get a working Square client for it, without salonLandings
 * needing its own copy of the {@code business}/{@code square_connection} tables or Square-credential
 * encryption — see ~/salonLandings/docs/multi-tenant-akpmu-design.md. Same gating pattern as
 * {@link InternalNotificationController}: {@code permitAll()} in {@code SecurityConfig}, auth
 * enforced here via {@code X-Internal-Api-Key}.
 */
@RestController
@RequestMapping("/api/internal/businesses")
public class InternalBusinessController {

    private final InternalApiProperties internalApi;
    private final BusinessRepository businesses;
    private final SquareConnectionService squareConnections;

    public InternalBusinessController(InternalApiProperties internalApi, BusinessRepository businesses,
                                      SquareConnectionService squareConnections) {
        this.internalApi = internalApi;
        this.businesses = businesses;
        this.squareConnections = squareConnections;
    }

    @GetMapping("/by-domain")
    public ResponseEntity<Map<String, Object>> byDomain(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String key,
            @RequestParam String domain) {
        if (!keyMatches(key)) {
            return ResponseEntity.status(401).build();
        }
        Business business = businesses.findByPublicDomain(domain).orElse(null);
        if (business == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of(
                "businessId", business.getId(),
                "name", business.getName(),
                "timezone", business.getTimezone()));
    }

    /** Plaintext Square access token/location for this business, decrypted on the fly — never
     * cached or logged here. Callers (salonLandings) are expected to cache this in-process for a
     * short TTL rather than call this per-request. 404 if the business doesn't exist or hasn't
     * connected Square yet — same "nothing to give you" outcome either way, so this endpoint never
     * leaks which case it is. */
    @GetMapping("/{businessId}/square-credentials")
    public ResponseEntity<Map<String, Object>> squareCredentials(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String key,
            @PathVariable Long businessId) {
        if (!keyMatches(key)) {
            return ResponseEntity.status(401).build();
        }
        SquareConnectionService.PlainCredentials credentials = squareConnections.plainCredentials(businessId)
                .orElse(null);
        if (credentials == null) {
            return ResponseEntity.notFound().build();
        }
        // HashMap, not Map.of(...) — applicationId is nullable (see SquareConnection's own doc:
        // "not consumed by any current API call... purely informational"), and Map.of() throws on
        // a null value.
        Map<String, Object> response = new HashMap<>();
        response.put("accessToken", credentials.accessToken());
        response.put("locationId", credentials.locationId());
        response.put("environment", credentials.environment().name());
        response.put("applicationId", credentials.applicationId());
        return ResponseEntity.ok(response);
    }

    private boolean keyMatches(String provided) {
        String expected = internalApi.getKey();
        if (expected == null || expected.isBlank() || provided == null) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8));
    }
}
