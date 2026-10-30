package com.salonreview.service;

import com.salonreview.domain.Permission;
import com.salonreview.domain.Role;
import com.salonreview.domain.UserPermission;
import com.salonreview.repo.UserPermissionRepository;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class UserPermissionService {

    private final UserPermissionRepository repository;

    public UserPermissionService(UserPermissionRepository repository) {
        this.repository = repository;
    }

    /** True when the role already includes the permission, so no grant row is needed or shown. */
    public static boolean impliedByRole(Role role, Permission permission) {
        return permission == Permission.SMS_INBOX && (role == Role.OWNER || role == Role.MANAGER);
    }

    public boolean has(Long userId, Role role, Long businessId, Permission permission) {
        if (impliedByRole(role, permission)) return true;
        if (userId == null || businessId == null) return false;
        return repository.existsByBusinessIdAndUserIdAndPermission(businessId, userId, permission.name());
    }

    public Set<Permission> effective(Long userId, Role role, Long businessId) {
        Set<Permission> result = EnumSet.noneOf(Permission.class);
        for (Permission p : Permission.values()) {
            if (impliedByRole(role, p)) result.add(p);
        }
        if (userId != null && businessId != null) {
            for (UserPermission row : repository.findByBusinessIdAndUserId(businessId, userId)) {
                try {
                    result.add(Permission.valueOf(row.getPermission()));
                } catch (IllegalArgumentException ignored) {
                    // a permission name removed from the enum: nothing to grant
                }
            }
        }
        return result;
    }

    public Set<Permission> granted(Long userId, Long businessId) {
        return repository.findByBusinessIdAndUserId(businessId, userId).stream()
                .map(r -> {
                    try {
                        return Permission.valueOf(r.getPermission());
                    } catch (IllegalArgumentException e) {
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Permission.class)));
    }

    public void set(Long businessId, Long userId, Permission permission, boolean granted, Long grantedBy) {
        boolean exists = repository.existsByBusinessIdAndUserIdAndPermission(businessId, userId, permission.name());
        if (granted && !exists) {
            repository.save(UserPermission.builder().businessId(businessId).userId(userId)
                    .permission(permission.name()).grantedBy(grantedBy).build());
        } else if (!granted && exists) {
            repository.deleteByBusinessIdAndUserIdAndPermission(businessId, userId, permission.name());
        }
    }
}
