package com.salonreview.config;

import com.salonreview.domain.AppUser;
import com.salonreview.domain.BusinessMembership;
import com.salonreview.domain.Role;
import com.salonreview.repo.AppUserRepository;
import com.salonreview.repo.BusinessMembershipRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * activeBusinessId resolution at login — see design.md D3. Every real account has exactly one
 * business_membership row today; zero or more than one fails loudly rather than guessing, since
 * there's no switcher UI yet to let an ambiguous login choose.
 */
class JpaUserDetailsServiceTest {

    private AppUserRepository users;
    private BusinessMembershipRepository memberships;
    private JpaUserDetailsService service;

    @BeforeEach
    void setUp() {
        users = mock(AppUserRepository.class);
        memberships = mock(BusinessMembershipRepository.class);
        service = new JpaUserDetailsService(users, memberships);
    }

    private static AppUser user(long id, String username) {
        return AppUser.builder().id(id).username(username).passwordHash("hash")
                .role(Role.OWNER).active(true).build();
    }

    @Test
    @DisplayName("exactly one membership row resolves activeBusinessId onto the principal")
    void resolvesActiveBusinessId() {
        when(users.findByUsername("olexandr.kara2")).thenReturn(Optional.of(user(1L, "olexandr.kara2")));
        when(memberships.findByUserId(1L)).thenReturn(List.of(
                BusinessMembership.builder().id(10L).businessId(7L).userId(1L).role(Role.OWNER).build()));

        AppUserPrincipal principal = (AppUserPrincipal) service.loadUserByUsername("olexandr.kara2");

        assertThat(principal.getActiveBusinessId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("zero membership rows fails loudly, never silently defaults to any business")
    void zeroMembershipsFailsLoudly() {
        when(users.findByUsername("orphan")).thenReturn(Optional.of(user(2L, "orphan")));
        when(memberships.findByUserId(2L)).thenReturn(List.of());

        assertThatThrownBy(() -> service.loadUserByUsername("orphan"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("more than one membership row fails loudly — no switcher UI exists yet to pick one")
    void multipleMembershipsFailsLoudly() {
        when(users.findByUsername("multi")).thenReturn(Optional.of(user(3L, "multi")));
        when(memberships.findByUserId(3L)).thenReturn(List.of(
                BusinessMembership.builder().id(11L).businessId(7L).userId(3L).role(Role.OWNER).build(),
                BusinessMembership.builder().id(12L).businessId(8L).userId(3L).role(Role.OWNER).build()));

        assertThatThrownBy(() -> service.loadUserByUsername("multi"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("unknown username still fails the same way as before (UsernameNotFoundException)")
    void unknownUsernameUnchanged() {
        when(users.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadUserByUsername("ghost"))
                .isInstanceOf(UsernameNotFoundException.class);
    }
}
