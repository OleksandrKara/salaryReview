package com.salonreview.web;

import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.tracking.TrackingConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.Instant;
import java.util.List;

/**
 * OWNER-only: Microsoft Clarity tracking-code project id per public site this business owns (see
 * {@link TrackingConfigService}'s own doc for why sites are keyed by hostname, not a per-business
 * singleton). Falls under the existing {@code /api/owner/**} matcher in {@link
 * com.salonreview.config.SecurityConfig} — no new security config needed. Not a secret, so unlike
 * {@code TwilioSmsSettingsController}/{@code MailchimpSettingsController} there's no masking —
 * GET returns the real value, PUT accepts the real value directly.
 */
@RestController
@RequestMapping("/api/owner/settings/tracking")
public class TrackingSettingsController {

    private final TrackingConfigService trackingConfig;
    private final CurrentBusinessContext currentBusinessContext;

    public TrackingSettingsController(TrackingConfigService trackingConfig, CurrentBusinessContext currentBusinessContext) {
        this.trackingConfig = trackingConfig;
        this.currentBusinessContext = currentBusinessContext;
    }

    @GetMapping
    public ResponseEntity<List<TrackingSiteDto>> list() {
        return ResponseEntity.ok(trackingConfig.list(currentBusinessContext.id()).stream().map(TrackingSiteDto::from).toList());
    }

    @PutMapping("/{hostname}")
    public ResponseEntity<TrackingSiteDto> update(@PathVariable String hostname,
                                                   @RequestBody TrackingUpdateRequest body, Principal principal) {
        TrackingConfigService.Site updated = trackingConfig.update(
                currentBusinessContext.id(), hostname, body.clarityProjectId(), principal.getName());
        return ResponseEntity.ok(TrackingSiteDto.from(updated));
    }

    public record TrackingSiteDto(String hostname, String siteLabel, String clarityProjectId,
                                   Instant updatedAt, String updatedBy) {
        static TrackingSiteDto from(TrackingConfigService.Site s) {
            return new TrackingSiteDto(s.hostname(), s.siteLabel(), s.clarityProjectId(), s.updatedAt(), s.updatedBy());
        }
    }

    /** {@code null}/blank clears it — same convention as every other settings PUT in this app. */
    public record TrackingUpdateRequest(String clarityProjectId) {}
}
