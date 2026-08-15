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
 * openspec/changes/multi-tenant-salon-platform/design.md D3. Every real account today has exactly
 * one membership row; a user with zero or more than one fails loudly here rather than silently
 * picking a business, since there's no switcher UI yet to let them choose (design.md D3/D12).
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
        if (rows.size() != 1) {
            throw new IllegalStateException("User '" + username + "' has " + rows.size()
                    + " business_membership rows (expected exactly 1) — no switcher UI exists yet to"
                    + " resolve which business this login belongs to");
        }
        return new AppUserPrincipal(user, rows.get(0).getBusinessId());
    }
}
