package com.salonreview.web;

import com.salonreview.config.AppUserPrincipal;
import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.domain.AppUser;
import com.salonreview.domain.Permission;
import com.salonreview.domain.Role;
import com.salonreview.repo.AppUserRepository;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.service.UserPermissionService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Owner-only (covered by the /api/users/** rule): which users of the active business hold which optional permissions. */
@RestController
@RequestMapping("/api/users/permissions")
public class UserPermissionController {

    private final AppUserRepository users;
    private final ProviderRepository providers;
    private final UserPermissionService permissions;
    private final CurrentBusinessContext currentBusinessContext;

    public UserPermissionController(AppUserRepository users, ProviderRepository providers,
                                    UserPermissionService permissions, CurrentBusinessContext currentBusinessContext) {
        this.users = users;
        this.providers = providers;
        this.permissions = permissions;
        this.currentBusinessContext = currentBusinessContext;
    }

    public record UserPermissionsDto(long userId, String username, String displayName, String role,
                                     List<String> granted, List<String> implied) {}

    public record PermissionsOverview(List<String> permissions, List<UserPermissionsDto> users) {}

    public record SetRequest(Long userId, String permission, boolean granted) {}

    @GetMapping
    public PermissionsOverview list() {
        Long businessId = currentBusinessContext.id();
        List<UserPermissionsDto> rows = users.findAllByBusinessIdOrderByUsernameAsc(businessId).stream()
                .filter(AppUser::isActive)
                .filter(u -> u.getRole() != Role.OWNER)
                .map(u -> {
                    List<String> implied = java.util.Arrays.stream(Permission.values())
                            .filter(p -> UserPermissionService.impliedByRole(u.getRole(), p))
                            .map(Permission::name).toList();
                    List<String> granted = permissions.granted(u.getId(), businessId).stream()
                            .map(Permission::name).toList();
                    String name = u.getProviderId() == null ? null
                            : providers.findById(u.getProviderId()).map(p -> p.getDisplayName()).orElse(null);
                    return new UserPermissionsDto(u.getId(), u.getUsername(), name, u.getRole().name(), granted, implied);
                })
                .toList();
        return new PermissionsOverview(java.util.Arrays.stream(Permission.values()).map(Permission::name).toList(), rows);
    }

    @PutMapping
    public void set(@RequestBody SetRequest body, @AuthenticationPrincipal AppUserPrincipal principal) {
        if (body == null || body.userId() == null || body.permission() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId and permission are required");
        }
        Permission permission;
        try {
            permission = Permission.valueOf(body.permission());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown permission");
        }
        Long businessId = currentBusinessContext.id();
        AppUser target = users.findById(body.userId())
                .filter(u -> businessId.equals(u.getBusinessId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        if (UserPermissionService.impliedByRole(target.getRole(), permission)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This role already includes the permission");
        }
        permissions.set(businessId, target.getId(), permission, body.granted(), principal.getUserId());
    }
}
