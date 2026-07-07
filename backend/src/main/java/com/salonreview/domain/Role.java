package com.salonreview.domain;

/**
 * Account role. Mapped to Spring Security authorities
 * {@code ROLE_OWNER/ROLE_MANAGER/ROLE_PROVIDER/ROLE_ADS_MANAGER}.
 *
 * <ul>
 *   <li>{@code OWNER} — super admin: salon config, all reports, tier grants, user management.</li>
 *   <li>{@code MANAGER} — all reports and tier grants; no user management.</li>
 *   <li>{@code PROVIDER} — read-only view of their own settlement + approve / request-correction.</li>
 *   <li>{@code ADS_MANAGER} — read-only access to the marketing pages only (variant performance,
 *       contacts, ads-attributed revenue); no write access anywhere, no other pages. For an
 *       external ads contractor who needs conversion numbers, not payroll/SOP/staff data.</li>
 * </ul>
 */
public enum Role {
    OWNER, MANAGER, PROVIDER, ADS_MANAGER
}
