package com.salonreview.web;

import com.salonreview.config.AppUserPrincipal;
import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.domain.SeoConnection;
import com.salonreview.seo.SeoConnectionService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

/**
 * OWNER-only "Connect SEO monitoring" settings for the calling business — falls under the
 * existing {@code /api/owner/**} matcher in {@link com.salonreview.config.SecurityConfig}, no new
 * security config needed. Same shape as {@link SquareConnectionController}: GET never returns a
 * secret in the clear (service-account JSON shown only as its {@code client_email}, PageSpeed key
 * masked to its last 4 characters); the frontend must never PUT those masked/derived values back —
 * same null-vs-unchanged contract as {@code SquareConnectionController}.
 */
@RestController
@RequestMapping("/api/owner/settings/seo")
public class SeoConnectionController {

    private final SeoConnectionService service;
    private final CurrentBusinessContext currentBusinessContext;

    public SeoConnectionController(SeoConnectionService service, CurrentBusinessContext currentBusinessContext) {
        this.service = service;
        this.currentBusinessContext = currentBusinessContext;
    }

    @GetMapping
    public SeoConnectionDto get() {
        Long businessId = currentBusinessContext.id();
        return service.get(businessId)
                .map(c -> toDto(c, service.serviceAccountEmail(c), service.maskedPagespeedApiKey(c)))
                .orElse(new SeoConnectionDto(null, false, null, null, null, false, null, null, null));
    }

    @PutMapping
    public SeoConnectionDto update(@RequestBody SeoConnectionUpdateRequest body,
                                    @AuthenticationPrincipal AppUserPrincipal me) {
        Long businessId = currentBusinessContext.id();
        SeoConnection saved = service.connect(businessId, body.gscServiceAccountJson(), body.ga4PropertyId(),
                body.ga4MeasurementId(), body.pagespeedApiKey(), me.getUserId());
        return toDto(saved, service.serviceAccountEmail(saved), service.maskedPagespeedApiKey(saved));
    }

    private SeoConnectionDto toDto(SeoConnection c, String serviceAccountEmail, String maskedPagespeedKey) {
        return new SeoConnectionDto(serviceAccountEmail, c.getGscServiceAccountJsonEncrypted() != null,
                c.getGa4PropertyId(), c.getGa4MeasurementId(), maskedPagespeedKey,
                c.getPagespeedApiKeyEncrypted() != null, c.getConnectedAt(), c.getLastSyncAt(), c.getLastSyncError());
    }

    public record SeoConnectionDto(String serviceAccountEmail, boolean serviceAccountSet,
                                    String ga4PropertyId, String ga4MeasurementId,
                                    String pagespeedApiKeyMasked, boolean pagespeedApiKeySet,
                                    Instant connectedAt, Instant lastSyncAt, String lastSyncError) {
    }

    /** {@code gscServiceAccountJson}/{@code pagespeedApiKey} null/blank = keep the existing value
     * unchanged (only meaningful when reconnecting to change just the GA4 IDs); required the first
     * time a business connects — see {@link SeoConnectionService#connect}. */
    public record SeoConnectionUpdateRequest(String gscServiceAccountJson, String ga4PropertyId,
                                               String ga4MeasurementId, String pagespeedApiKey) {
    }
}
