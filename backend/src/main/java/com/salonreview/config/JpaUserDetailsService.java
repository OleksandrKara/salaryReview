package com.salonreview.config;

import com.salonreview.domain.AppUser;
import com.salonreview.domain.BusinessMembership;
import com.salonreview.repo.AppUserRepository;
import com.salonreview.repo.BusinessMembershipRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Loads {@link AppUserPrincipal}s from the {@code app_user} table for authentication, resolving each
 * login's {@code activeBusinessId} from its {@code business_membership} row(s) — see
 * openspec/changes/multi-tenant-salon-platform/design.md D3. A login with several memberships
 * starts in its home business and switches in-session (2026-10-07); zero memberships fails loudly.
 */
@Service
public class JpaUserDetailsService implements UserDetailsService {

    private final AppUserRepository users;
    private final BusinessMembershipRepository memberships;

    public JpaUserDetailsService(AppUserRepository users, BusinessMembershipRepository memberships) {
        this.users = users;
        this.memberships = memberships;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        AppUser user = users.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user: " + username));
        List<BusinessMembership> rows = memberships.findByUserId(user.getId());
        if (rows.isEmpty()) {
            throw new IllegalStateException("User '" + username + "' has no business_membership row");
        }
        // A login can belong to several businesses (owner request 2026-10-07: managers who work for
        // both studios with one email). It starts in its home business (app_user.business_id) and
        // switches from there (BusinessSwitchController, AdminMenu, ?business= deep links).
        Long active = rows.stream().map(BusinessMembership::getBusinessId)
                .filter(id -> id.equals(user.getBusinessId())).findFirst()
                .orElse(rows.stream().map(BusinessMembership::getBusinessId).min(Long::compare).orElseThrow());
        return new AppUserPrincipal(user, active);
    }
}
