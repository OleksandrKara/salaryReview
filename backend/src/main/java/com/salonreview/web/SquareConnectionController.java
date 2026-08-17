package com.salonreview.web;

import com.salonreview.config.AppUserPrincipal;
import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.config.SquareProperties;
import com.salonreview.domain.SquareConnection;
import com.salonreview.square.SquareConnectionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

/**
 * OWNER-only "Connect Square" settings for the calling business — falls under the existing
 * {@code /api/owner/**} matcher in {@link com.salonreview.config.SecurityConfig}, no new security
 * config needed. GET only ever returns a masked token (see
 * {@link SquareConnectionService#maskedAccessToken}); the frontend must never PUT that masked value
 * back — same null-vs-unchanged contract as {@link TelegramSettingsController}.
 */
@RestController
@RequestMapping("/api/owner/settings/square")
public class SquareConnectionController {

    private final SquareConnectionService service;
    private final CurrentBusinessContext currentBusinessContext;
    private final String publicBaseUrl;

    public SquareConnectionController(SquareConnectionService service,
                                       CurrentBusinessContext currentBusinessContext,
                                       @Value("${app.public-base-url}") String publicBaseUrl) {
        this.service = service;
        this.currentBusinessContext = currentBusinessContext;
        this.publicBaseUrl = publicBaseUrl;
    }

    @GetMapping
    public SquareConnectionDto get() {
        Long businessId = currentBusinessContext.id();
        return service.get(businessId)
                .map(c -> toDto(c, service.maskedAccessToken(c), service.maskedWebhookSignatureKey(c), businessId))
                .orElse(new SquareConnectionDto(null, false, null, null, null, null, null, null,
                        null, false, webhookNotificationUrl(businessId)));
    }

    @PutMapping
    public SquareConnectionDto update(@RequestBody SquareConnectionUpdateRequest body,
                                       @AuthenticationPrincipal AppUserPrincipal me) {
        if (body.environment() == null || body.environment().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "environment is required");
        }
        SquareProperties.Environment environment;
        try {
            environment = SquareProperties.Environment.valueOf(body.environment().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "environment must be SANDBOX or PRODUCTION");
        }
        if (body.locationId() == null || body.locationId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "locationId is required");
        }

        Long businessId = currentBusinessContext.id();
        SquareConnection saved = service.connect(businessId, environment,
                body.accessToken(), body.locationId().trim(), body.applicationId(), me.getUserId());
        if (body.webhookSignatureKey() != null && !body.webhookSignatureKey().isBlank()) {
            saved = service.updateWebhookSignatureKey(businessId, body.webhookSignatureKey());
        }
        return toDto(saved, service.maskedAccessToken(saved), service.maskedWebhookSignatureKey(saved), businessId);
    }

    private SquareConnectionDto toDto(SquareConnection c, String maskedToken, String maskedWebhookKey, Long businessId) {
        return new SquareConnectionDto(maskedToken, c.getAccessTokenEncrypted() != null,
                c.getEnvironment().name(), c.getLocationId(), c.getApplicationId(), c.getMerchantId(),
                c.getConnectedAt(), c.getLastSyncAt(),
                maskedWebhookKey, c.getWebhookSignatureKeyEncrypted() != null, webhookNotificationUrl(businessId));
    }

    /** The exact URL this business's owner needs to paste into Square's Developer Dashboard
     * webhook subscription — must match, byte-for-byte, what {@link
     * com.salonreview.square.webhook.SquareWebhookController}'s per-business route computes, since
     * it's part of the HMAC input Square signs, not just documentation. Purely informational —
     * computed fresh on every GET, never stored. */
    private String webhookNotificationUrl(Long businessId) {
        return publicBaseUrl + "/api/public/webhooks/square/" + businessId;
    }

    public record SquareConnectionDto(String accessTokenMasked, boolean accessTokenSet, String environment,
                                       String locationId, String applicationId, String merchantId,
                                       Instant connectedAt, Instant lastSyncAt,
                                       String webhookSignatureKeyMasked, boolean webhookSignatureKeySet,
                                       String webhookNotificationUrl) {
    }

    /** {@code accessToken} null/blank = keep the existing token (only meaningful when reconnecting
     * to change just the location or environment); required the first time. {@code
     * webhookSignatureKey} null/blank = keep the existing key unchanged (or leave unconfigured) —
     * same convention, see {@link com.salonreview.square.SquareConnectionService#updateWebhookSignatureKey}. */
    public record SquareConnectionUpdateRequest(String accessToken, String environment, String locationId,
                                                  String applicationId, String webhookSignatureKey) {
    }
}
