package com.salonreview.web;

import com.salonreview.config.AppUserPrincipal;
import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.config.SquareProperties;
import com.salonreview.domain.SquareConnection;
import com.salonreview.square.SquareConnectionService;
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

    public SquareConnectionController(SquareConnectionService service,
                                       CurrentBusinessContext currentBusinessContext) {
        this.service = service;
        this.currentBusinessContext = currentBusinessContext;
    }

    @GetMapping
    public SquareConnectionDto get() {
        return service.get(currentBusinessContext.id())
                .map(c -> toDto(c, service.maskedAccessToken(c)))
                .orElse(new SquareConnectionDto(null, false, null, null, null, null, null, null));
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

        SquareConnection saved = service.connect(currentBusinessContext.id(), environment,
                body.accessToken(), body.locationId().trim(), body.applicationId(), me.getUserId());
        return toDto(saved, service.maskedAccessToken(saved));
    }

    private static SquareConnectionDto toDto(SquareConnection c, String maskedToken) {
        return new SquareConnectionDto(maskedToken, c.getAccessTokenEncrypted() != null,
                c.getEnvironment().name(), c.getLocationId(), c.getApplicationId(), c.getMerchantId(),
                c.getConnectedAt(), c.getLastSyncAt());
    }

    public record SquareConnectionDto(String accessTokenMasked, boolean accessTokenSet, String environment,
                                       String locationId, String applicationId, String merchantId,
                                       Instant connectedAt, Instant lastSyncAt) {
    }

    /** {@code accessToken} null/blank = keep the existing token (only meaningful when reconnecting
     * to change just the location or environment); required the first time. */
    public record SquareConnectionUpdateRequest(String accessToken, String environment, String locationId,
                                                  String applicationId) {
    }
}
