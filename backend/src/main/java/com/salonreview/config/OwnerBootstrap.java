package com.salonreview.config;

import com.salonreview.domain.AppUser;
import com.salonreview.domain.BusinessMembership;
import com.salonreview.domain.PlatformAdmin;
import com.salonreview.domain.Role;
import com.salonreview.repo.AppUserRepository;
import com.salonreview.repo.BusinessMembershipRepository;
import com.salonreview.repo.BusinessRepository;
import com.salonreview.repo.PlatformAdminRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

/**
 * Seeds the first OWNER account from {@code APP_OWNER_USERNAME}/{@code APP_OWNER_PASSWORD} when the
 * {@code app_user} table is empty, so the existing {@code .env} keeps working and there's always a
 * way in to create the rest of the users. Idempotent: does nothing once any account exists. Runs at
 * app startup with no HTTP request in flight, so the business is resolved via
 * {@code BusinessRepository.sole()} (design.md D9's pattern), not {@code CurrentBusinessContext}.
 */
@Configuration
public class OwnerBootstrap {

    private static final Logger log = LoggerFactory.getLogger(OwnerBootstrap.class);

    @Bean
    ApplicationRunner seedOwner(AppUserRepository users, BusinessMembershipRepository memberships,
                                BusinessRepository businesses, PlatformAdminRepository platformAdmins,
                                PasswordEncoder encoder,
                                @Value("${app.auth.username:owner}") String username,
                                @Value("${app.auth.password:}") String password) {
        return args -> {
            if (users.count() > 0) return;
            if (password == null || password.isBlank()) {
                log.warn("No app_user accounts and APP_OWNER_PASSWORD is blank — no owner seeded. "
                        + "Set APP_OWNER_PASSWORD and restart to bootstrap the first login.");
                return;
            }
            Long businessId = businesses.sole().getId();
            AppUser owner = users.save(AppUser.builder()
                    .businessId(businessId)
                    .username(username)
                    .passwordHash(encoder.encode(password))
                    .role(Role.OWNER)
                    .active(true)
                    .build());
            // Without this row JpaUserDetailsService fails loudly on this account's first login —
            // every app_user needs exactly one business_membership row (design.md D3).
            memberships.save(BusinessMembership.builder()
                    .businessId(businessId).userId(owner.getId()).role(Role.OWNER).build());
            // Phase 5.2: whoever bootstraps a fresh instance is, by definition, its platform
            // operator — the very first OWNER this app ever creates should also be able to reach
            // /api/platform/**, not get silently locked out of it.
            platformAdmins.save(PlatformAdmin.builder().userId(owner.getId()).build());
            log.info("Seeded initial OWNER account '{}' from environment (also platform_admin).", username);
        };
    }

    /** Phase 5.2, one-time backfill for an already-bootstrapped instance (like production, where
     * {@link #seedOwner} above already ran months before platform_admin existed and won't run
     * again): grants platform_admin to the account username 'owner' resolves to, if one exists and
     * doesn't already have it. Idempotent — same "safe to run unattended on every boot, forever"
     * shape as {@code SquareConnectionBootstrap}. Does nothing once platform_admin has any row at
     * all, so it never overrides a deliberate later change (e.g. revoking/reassigning access). */
    @Bean
    ApplicationRunner backfillPlatformAdmin(AppUserRepository users, PlatformAdminRepository platformAdmins,
                                             @Value("${app.auth.username:owner}") String username) {
        return args -> {
            if (platformAdmins.count() > 0) return;
            Optional<AppUser> owner = users.findByUsername(username);
            if (owner.isEmpty()) return;
            platformAdmins.save(PlatformAdmin.builder().userId(owner.get().getId()).build());
            log.info("Backfilled platform_admin for existing account '{}'.", username);
        };
    }
}
