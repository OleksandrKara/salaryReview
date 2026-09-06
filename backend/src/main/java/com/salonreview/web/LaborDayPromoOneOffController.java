package com.salonreview.web;

import com.salonreview.config.AppUserPrincipal;
import com.salonreview.repo.PlatformAdminRepository;
import com.salonreview.sms.LaborDayPromoOneOffService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Platform-admin-only trigger for {@link LaborDayPromoOneOffService} (owner request 2026-09-06) —
 * same access-control shape as {@link ColorBoosterWinbackOneOffController}: {@code /api/platform/**}
 * already requires {@code hasRole("OWNER")} at the URL level, this additionally requires a
 * {@code platform_admin} row for the caller.
 *
 * <p>{@code GET} is a dry run (resolves and renders everything, never sends, never writes a row) —
 * safe to call repeatedly while reviewing the candidate list. {@code POST} is the real send.
 */
@RestController
@RequestMapping("/api/platform/one-off/labor-day-promo")
public class LaborDayPromoOneOffController {

    private final LaborDayPromoOneOffService service;
    private final PlatformAdminRepository platformAdmins;

    public LaborDayPromoOneOffController(LaborDayPromoOneOffService service, PlatformAdminRepository platformAdmins) {
        this.service = service;
        this.platformAdmins = platformAdmins;
    }

    @GetMapping
    public List<LaborDayPromoOneOffService.CandidateResult> preview(@RequestParam Long businessId,
                                                                      @AuthenticationPrincipal AppUserPrincipal principal) {
        requirePlatformAdmin(principal);
        return service.run(businessId, true);
    }

    @PostMapping
    public List<LaborDayPromoOneOffService.CandidateResult> execute(@RequestParam Long businessId,
                                                                      @AuthenticationPrincipal AppUserPrincipal principal) {
        requirePlatformAdmin(principal);
        return service.run(businessId, false);
    }

    private void requirePlatformAdmin(AppUserPrincipal principal) {
        if (!platformAdmins.existsById(principal.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Platform admin access required");
        }
    }
}
