package com.salonreview.web;

import com.salonreview.config.AppUserPrincipal;
import com.salonreview.repo.PlatformAdminRepository;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClientProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

/**
 * Platform-admin-only tool to overwrite a Square customer's email address on file (owner request
 * 2026-09-06, after an automated domain-typo scan surfaced obviously-mistyped addresses like
 * "gmail.con" for business 2). Generic and reusable — not tied to any one campaign — the caller
 * supplies the exact (customerId, newEmail) pairs; this never guesses a correction itself. Same
 * access-control shape as {@link ColorBoosterWinbackOneOffController}: {@code /api/platform/**}
 * already requires {@code hasRole("OWNER")} at the URL level, this additionally requires a
 * {@code platform_admin} row.
 */
@RestController
@RequestMapping("/api/platform/one-off/fix-customer-emails")
public class CustomerEmailCorrectionController {

    private final SquareClientProvider squareClientProvider;
    private final PlatformAdminRepository platformAdmins;

    public CustomerEmailCorrectionController(SquareClientProvider squareClientProvider, PlatformAdminRepository platformAdmins) {
        this.squareClientProvider = squareClientProvider;
        this.platformAdmins = platformAdmins;
    }

    public record Correction(String customerId, String newEmail) {}
    public record FixResult(String customerId, String newEmail, String state, String detail) {}

    @PostMapping
    public List<FixResult> fix(@RequestParam Long businessId, @RequestBody List<Correction> corrections,
                                @AuthenticationPrincipal AppUserPrincipal principal) {
        if (!platformAdmins.existsById(principal.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Platform admin access required");
        }
        SquareClient square = squareClientProvider.forBusiness(businessId);
        List<FixResult> results = new ArrayList<>();
        for (Correction c : corrections) {
            if (c.customerId() == null || c.newEmail() == null || c.newEmail().isBlank()) {
                results.add(new FixResult(c.customerId(), c.newEmail(), "SKIPPED_INVALID_INPUT", null));
                continue;
            }
            try {
                square.updateCustomerEmail(c.customerId(), c.newEmail());
                results.add(new FixResult(c.customerId(), c.newEmail(), "FIXED", null));
            } catch (Exception e) {
                results.add(new FixResult(c.customerId(), c.newEmail(), "FAILED", e.getMessage()));
            }
        }
        return results;
    }
}
