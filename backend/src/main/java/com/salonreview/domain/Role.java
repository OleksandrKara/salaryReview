package com.salonreview.domain;

/**
 * Account role. Mapped to Spring Security authorities {@code ROLE_OWNER/ROLE_MANAGER/ROLE_PROVIDER}.
 *
 * <ul>
 *   <li>{@code OWNER} — super admin: salon config, all reports, tier grants, user management.</li>
 *   <li>{@code MANAGER} — all reports and tier grants; no user management.</li>
 *   <li>{@code PROVIDER} — read-only view of their own settlement + approve / request-correction.</li>
 * </ul>
 */
public enum Role {
    OWNER, MANAGER, PROVIDER
}
