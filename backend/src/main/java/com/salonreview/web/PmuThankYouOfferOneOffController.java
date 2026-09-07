package com.salonreview.web;

import com.salonreview.config.AppUserPrincipal;
import com.salonreview.repo.PlatformAdminRepository;
import com.salonreview.sms.MailchimpBatchCampaignService;
import com.salonreview.sms.PmuThankYouOfferOneOffService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Platform-admin-only trigger for {@link PmuThankYouOfferOneOffService} (owner request
 * 2026-09-06/07) — same access-control shape as {@link ColorBoosterWinbackOneOffController}:
 * {@code /api/platform/**} already requires {@code hasRole("OWNER")} at the URL level, this
 * additionally requires a {@code platform_admin} row.
 *
 * <p>{@code GET} is a read-only count preview (resolves the exact recipient list, never touches
 * Mailchimp beyond two read-only member-status scans) — safe to call repeatedly while reviewing
 * the audience. {@code POST} is the real, irreversible send: one shared Mailchimp campaign to
 * every resolved recipient.
 */
@RestController
@RequestMapping("/api/platform/one-off/pmu-thank-you-offer")
public class PmuThankYouOfferOneOffController {

    private final PmuThankYouOfferOneOffService service;
    private final PlatformAdminRepository platformAdmins;

    public PmuThankYouOfferOneOffController(PmuThankYouOfferOneOffService service, PlatformAdminRepository platformAdmins) {
        this.service = service;
        this.platformAdmins = platformAdmins;
    }

    @GetMapping
    public PmuThankYouOfferOneOffService.PreviewResult preview(@AuthenticationPrincipal AppUserPrincipal principal) throws Exception {
        requirePlatformAdmin(principal);
        return service.preview();
    }

    @PostMapping
    public MailchimpBatchCampaignService.BatchSendResult execute(@AuthenticationPrincipal AppUserPrincipal principal) throws Exception {
        requirePlatformAdmin(principal);
        return service.send();
    }

    private void requirePlatformAdmin(AppUserPrincipal principal) {
        if (!platformAdmins.existsById(principal.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Platform admin access required");
        }
    }
}
