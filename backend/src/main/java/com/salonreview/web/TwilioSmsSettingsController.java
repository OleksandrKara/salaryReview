package com.salonreview.web;

import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.domain.TwilioSmsConfig;
import com.salonreview.sms.TwilioSmsConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.Instant;

/**
 * OWNER-only settings for outbound SMS (Twilio). Falls under the existing {@code /api/owner/**}
 * matcher in {@link com.salonreview.config.SecurityConfig} — no new security config needed. GET
 * only ever returns masked key/secret values; the frontend must not PUT those masked values back
 * (see {@link TwilioSmsConfigService#update}'s null-vs-empty-string contract). In practice only
 * Business A can ever reach this controller ({@link com.salonreview.config.SmsBusinessScopeFilter}
 * blocks every other business), but resolving the business via the session rather than hardcoding
 * it keeps this controller itself correct independent of that filter.
 */
@RestController
@RequestMapping("/api/owner/settings/sms")
public class TwilioSmsSettingsController {

    private final TwilioSmsConfigService configService;
    private final CurrentBusinessContext currentBusinessContext;

    public TwilioSmsSettingsController(TwilioSmsConfigService configService, CurrentBusinessContext currentBusinessContext) {
        this.configService = configService;
        this.currentBusinessContext = currentBusinessContext;
    }

    @GetMapping
    public ResponseEntity<TwilioSmsSettingsDto> get() {
        return ResponseEntity.ok(toDto(configService.get(currentBusinessContext.id())));
    }

    @PutMapping
    public ResponseEntity<TwilioSmsSettingsDto> update(@RequestBody TwilioSmsSettingsUpdateRequest body, Principal principal) {
        TwilioSmsConfig updated = configService.update(
                body.accountSid(), body.apiKey(), body.apiSecret(), body.fromPhoneNumber(), principal.getName(),
                currentBusinessContext.id());
        return ResponseEntity.ok(toDto(updated));
    }

    private static TwilioSmsSettingsDto toDto(TwilioSmsConfig cfg) {
        return new TwilioSmsSettingsDto(
                mask(cfg.getAccountSid()), cfg.getAccountSid() != null,
                mask(cfg.getApiKey()), cfg.getApiKey() != null,
                mask(cfg.getApiSecret()), cfg.getApiSecret() != null,
                cfg.getFromPhoneNumber(),
                cfg.getUpdatedAt(), cfg.getUpdatedBy());
    }

    private static String mask(String value) {
        if (value == null || value.isBlank()) return null;
        return value.length() <= 4 ? "••••" : "••••" + value.substring(value.length() - 4);
    }

    public record TwilioSmsSettingsDto(String accountSidMasked, boolean accountSidSet,
                                        String apiKeyMasked, boolean apiKeySet,
                                        String apiSecretMasked, boolean apiSecretSet,
                                        String fromPhoneNumber,
                                        Instant updatedAt, String updatedBy) {
    }

    /** {@code null} field = leave unchanged; {@code ""} = clear. See {@link TwilioSmsConfigService#update}. */
    public record TwilioSmsSettingsUpdateRequest(String accountSid, String apiKey, String apiSecret,
                                                  String fromPhoneNumber) {
    }
}
