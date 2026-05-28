package com.salonreview.config;

import com.salonreview.domain.AppUser;
import com.salonreview.domain.Role;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * The authenticated principal. Beyond the username/password Spring needs, it carries the account's
 * {@code userId}, {@link Role} and (for providers) {@code providerId}, so controllers can scope to
 * the caller — e.g. "show only my settlement" — without another lookup. Resolve it from a controller
 * via an {@code @AuthenticationPrincipal AppUserPrincipal} argument.
 */
public class AppUserPrincipal implements UserDetails {

    private final Long userId;
    private final String username;
    private final String passwordHash;
    private final Role role;
    private final Long providerId;
    private final boolean active;

    public AppUserPrincipal(AppUser u) {
        this.userId = u.getId();
        this.username = u.getUsername();
        this.passwordHash = u.getPasswordHash();
        this.role = u.getRole();
        this.providerId = u.getProviderId();
        this.active = u.isActive();
    }

    public Long getUserId() { return userId; }
    public Role getRole() { return role; }
    public Long getProviderId() { return providerId; }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override public String getPassword() { return passwordHash; }
    @Override public String getUsername() { return username; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return active; }
}
