package com.salonreview.web;

import com.salonreview.config.AppUserPrincipal;
import com.salonreview.domain.Business;
import com.salonreview.repo.PlatformAdminRepository;
import com.salonreview.service.BusinessProvisioningService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

/**
 * Platform-admin-only business creation (Phase 5.1/5.2, design.md D4) — falls under
 * {@code /api/platform/**} in {@link com.salonreview.config.SecurityConfig} (still requires
 * {@code hasRole("OWNER")} at the URL level as a baseline), but every endpoint here additionally
 * requires a {@code platform_admin} row for the caller — being an OWNER of *some* business is not
 * enough on its own. Before this, any business's owner could list every business on the platform
 * and create new ones with arbitrary owner credentials; see V107's own migration comment.
 */
@RestController
@RequestMapping("/api/platform/businesses")
public class PlatformBusinessController {

    private final BusinessProvisioningService service;
    private final PlatformAdminRepository platformAdmins;

    public PlatformBusinessController(BusinessProvisioningService service, PlatformAdminRepository platformAdmins) {
        this.service = service;
        this.platformAdmins = platformAdmins;
    }

    @GetMapping
    public List<BusinessDto> list(@AuthenticationPrincipal AppUserPrincipal principal) {
        requirePlatformAdmin(principal);
        return service.list().stream().map(PlatformBusinessController::toDto).toList();
    }

    @PostMapping
    public ResponseEntity<BusinessDto> create(@RequestBody CreateBusinessRequest body,
                                               @AuthenticationPrincipal AppUserPrincipal principal) {
        requirePlatformAdmin(principal);
        Business created = service.create(body.name(), body.shortCode(), body.timezone(),
                body.ownerUsername(), body.ownerPassword());
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(created));
    }

    private void requirePlatformAdmin(AppUserPrincipal principal) {
        if (!platformAdmins.existsById(principal.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Platform admin access required");
        }
    }

    private static BusinessDto toDto(Business b) {
        return new BusinessDto(b.getId(), b.getName(), b.getShortCode(), b.getTimezone(), b.isActive(), b.getCreatedAt());
    }

    public record BusinessDto(Long id, String name, String shortCode, String timezone, boolean active,
                               Instant createdAt) {
    }

    public record CreateBusinessRequest(String name, String shortCode, String timezone,
                                         String ownerUsername, String ownerPassword) {
    }
}
