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
 * {@code userId}, {@link Role}, (for providers) {@code providerId}, and {@code activeBusinessId} —
 * the single {@code business_membership} row resolved at login by {@code JpaUserDetailsService} — so
 * controllers can scope to the caller without another lookup. Resolve it from a controller via an
 * {@code @AuthenticationPrincipal AppUserPrincipal} argument.
 */
public class AppUserPrincipal implements UserDetails {

    // Sessions are DB-backed (spring_session_jdbc) and persist across deploys — UserDetails extends
    // Serializable, so without a pinned UID, any field added/removed here recomputes Java's default
    // serialVersionUID and makes every session created before that deploy throw InvalidClassException
    // on its very next request (a bare 500, not a clean 401 — this happened for real: PR #351 added
    // activeBusinessId below with no pinned UID, silently breaking every already-logged-in session
    // from that deploy onward until the stale rows were manually cleared). Bump this only if the
    // stored shape is deliberately being made incompatible (never for a routine field change).
    private static final long serialVersionUID = 1L;

    private final Long userId;
    private final String username;
    private final String passwordHash;
    private final Role role;
    private final Long providerId;
    private final boolean active;
    private final Long activeBusinessId;

    public AppUserPrincipal(AppUser u, Long activeBusinessId) {
        this.userId = u.getId();
        this.username = u.getUsername();
        this.passwordHash = u.getPasswordHash();
        this.role = u.getRole();
        this.providerId = u.getProviderId();
        this.active = u.isActive();
        this.activeBusinessId = activeBusinessId;
    }

    public Long getUserId() { return userId; }
    public Role getRole() { return role; }
    public Long getProviderId() { return providerId; }
    public Long getActiveBusinessId() { return activeBusinessId; }

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
