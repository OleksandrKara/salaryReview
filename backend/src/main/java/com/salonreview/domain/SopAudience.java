package com.salonreview.domain;

import java.util.List;

/**
 * Who a SOP targets — determines both visibility and who must acknowledge.
 *
 * <ul>
 *   <li>{@code MANAGER} — managers only.</li>
 *   <li>{@code PROVIDER} — providers only.</li>
 *   <li>{@code BOTH} — managers and providers.</li>
 * </ul>
 */
public enum SopAudience {
    MANAGER, PROVIDER, BOTH;

    /** Whether a caller's role is included in this audience (owners are never an audience member). */
    public boolean includes(Role role) {
        return switch (this) {
            case MANAGER -> role == Role.MANAGER;
            case PROVIDER -> role == Role.PROVIDER;
            case BOTH -> role == Role.MANAGER || role == Role.PROVIDER;
        };
    }

    /** The staff roles this audience covers — used to build the acknowledgment roster. */
    public List<Role> roles() {
        return switch (this) {
            case MANAGER -> List.of(Role.MANAGER);
            case PROVIDER -> List.of(Role.PROVIDER);
            case BOTH -> List.of(Role.MANAGER, Role.PROVIDER);
        };
    }
}
