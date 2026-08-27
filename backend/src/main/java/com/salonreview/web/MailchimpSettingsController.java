package com.salonreview.web;

import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.domain.MailchimpConfig;
import com.salonreview.sms.MailchimpConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.Instant;

/**
 * OWNER-only settings for outbound marketing email (Mailchimp) — same shape as
 * {@link TwilioSmsSettingsController}, see that class's own doc for the masking/null-vs-empty
 * conventions this mirrors exactly. Falls under the existing {@code /api/owner/**} matcher in
 * {@link com.salonreview.config.SecurityConfig} — no new security config needed.
 */
@RestController
@RequestMapping("/api/owner/settings/mailchimp")
public class MailchimpSettingsController {

    private final MailchimpConfigService configService;
    private final CurrentBusinessContext currentBusinessContext;

    public MailchimpSettingsController(MailchimpConfigService configService, CurrentBusinessContext currentBusinessContext) {
        this.configService = configService;
        this.currentBusinessContext = currentBusinessContext;
    }

    @GetMapping
    public ResponseEntity<MailchimpSettingsDto> get() {
        return ResponseEntity.ok(toDto(configService.get(currentBusinessContext.id())));
    }

    @PutMapping
    public ResponseEntity<MailchimpSettingsDto> update(@RequestBody MailchimpSettingsUpdateRequest body, Principal principal) {
        MailchimpConfig updated = configService.update(
                body.apiKey(), body.audienceId(), body.fromName(), body.replyToEmail(),
                principal.getName(), currentBusinessContext.id());
        return ResponseEntity.ok(toDto(updated));
    }

    private static MailchimpSettingsDto toDto(MailchimpConfig cfg) {
        return new MailchimpSettingsDto(
                mask(cfg.getApiKey()), cfg.getApiKey() != null,
                cfg.getAudienceId(), cfg.getFromName(), cfg.getReplyToEmail(),
                cfg.isConfigured(), cfg.getUpdatedAt(), cfg.getUpdatedBy());
    }

    private static String mask(String value) {
        if (value == null || value.isBlank()) return null;
        return value.length() <= 4 ? "••••" : "••••" + value.substring(value.length() - 4);
    }

    public record MailchimpSettingsDto(String apiKeyMasked, boolean apiKeySet,
                                        String audienceId, String fromName, String replyToEmail,
                                        boolean configured, Instant updatedAt, String updatedBy) {
    }

    /** {@code null} field = leave unchanged; {@code ""} = clear. See
     * {@link MailchimpConfigService#update}. */
    public record MailchimpSettingsUpdateRequest(String apiKey, String audienceId,
                                                   String fromName, String replyToEmail) {
    }
}
