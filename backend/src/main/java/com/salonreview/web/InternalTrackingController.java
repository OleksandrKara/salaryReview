package com.salonreview.web;

import com.salonreview.config.InternalApiProperties;
import com.salonreview.tracking.TrackingConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Map;

/**
 * Service-to-service: lets akluxnails-home and salonLandings resolve their own Microsoft Clarity
 * project id at render time, without either app holding a copy of {@code tracking_config} or the
 * owner having to redeploy just to change an id. Same {@code X-Internal-Api-Key} gating as {@link
 * InternalBusinessController#byDomain} — deliberately the same {@code ?domain=} shape too, since
 * both are "resolve something for this hostname" lookups; a caller that already knows how to call
 * one already knows how to call the other. {@code permitAll()} in {@link
 * com.salonreview.config.SecurityConfig}, auth enforced here.
 */
@RestController
@RequestMapping("/api/internal/tracking-config")
public class InternalTrackingController {

    private final InternalApiProperties internalApi;
    private final TrackingConfigService trackingConfig;

    public InternalTrackingController(InternalApiProperties internalApi, TrackingConfigService trackingConfig) {
        this.internalApi = internalApi;
        this.trackingConfig = trackingConfig;
    }

    /** Always 200 — "no config for this hostname yet" is a normal state (a brand-new site, or one
     * the owner hasn't pasted an id into yet), not an error; the caller just renders nothing. */
    @GetMapping
    public ResponseEntity<Map<String, Object>> byDomain(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String key,
            @RequestParam String domain) {
        if (!keyMatches(key)) {
            return ResponseEntity.status(401).build();
        }
        String clarityProjectId = trackingConfig.clarityProjectIdFor(domain);
        return ResponseEntity.ok(Collections.singletonMap("clarityProjectId", clarityProjectId));
    }

    private boolean keyMatches(String provided) {
        String expected = internalApi.getKey();
        if (expected == null || expected.isBlank() || provided == null) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8));
    }
}
