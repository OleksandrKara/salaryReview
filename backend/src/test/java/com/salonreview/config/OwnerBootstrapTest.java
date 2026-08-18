package com.salonreview.config;

import com.salonreview.domain.AppUser;
import com.salonreview.domain.Business;
import com.salonreview.domain.PlatformAdmin;
import com.salonreview.domain.Role;
import com.salonreview.repo.AppUserRepository;
import com.salonreview.repo.BusinessMembershipRepository;
import com.salonreview.repo.BusinessRepository;
import com.salonreview.repo.PlatformAdminRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Phase 5.2: whoever bootstraps a fresh instance is its platform operator; an already-bootstrapped
 * instance (like production) needs a one-time backfill instead. See OwnerBootstrap's own doc. */
class OwnerBootstrapTest {

    private final OwnerBootstrap bootstrap = new OwnerBootstrap();
    private AppUserRepository users;
    private BusinessMembershipRepository memberships;
    private BusinessRepository businesses;
    private PlatformAdminRepository platformAdmins;
    private PasswordEncoder encoder;

    @BeforeEach
    void setUp() {
        users = mock(AppUserRepository.class);
        memberships = mock(BusinessMembershipRepository.class);
        businesses = mock(BusinessRepository.class);
        platformAdmins = mock(PlatformAdminRepository.class);
        encoder = mock(PasswordEncoder.class);
        when(encoder.encode(any())).thenReturn("hashed");
        when(users.save(any())).thenAnswer(inv -> {
            AppUser u = inv.getArgument(0);
            u.setId(1L);
            return u;
        });
    }

    @Test
    @DisplayName("fresh bootstrap: seeds the owner AND grants it platform_admin")
    void freshBootstrapSeedsOwnerAsPlatformAdmin() throws Exception {
        when(users.count()).thenReturn(0L);
        when(businesses.sole()).thenReturn(Business.builder().id(1L).build());
        ApplicationRunner runner = bootstrap.seedOwner(users, memberships, businesses, platformAdmins, encoder, "owner", "pw");

        runner.run(mock(ApplicationArguments.class));

        var captor = org.mockito.ArgumentCaptor.forClass(PlatformAdmin.class);
        verify(platformAdmins).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("fresh bootstrap: no-op (no owner seeded, no platform_admin touched) when accounts already exist")
    void freshBootstrapNoOpWhenAccountsExist() throws Exception {
        when(users.count()).thenReturn(3L);
        ApplicationRunner runner = bootstrap.seedOwner(users, memberships, businesses, platformAdmins, encoder, "owner", "pw");

        runner.run(mock(ApplicationArguments.class));

        verifyNoInteractions(platformAdmins);
        verify(users, never()).save(any());
    }

    @Test
    @DisplayName("2026-08-18: backfill grants platform_admin to the existing 'owner' account on an "
            + "already-bootstrapped instance (production) — the gap that made a straight Flyway-migration "
            + "INSERT unreliable (migrations run before OwnerBootstrap ever creates the row)")
    void backfillGrantsPlatformAdminToExistingOwner() throws Exception {
        when(platformAdmins.count()).thenReturn(0L);
        when(users.findByUsername("owner")).thenReturn(Optional.of(AppUser.builder().id(1L).username("owner")
                .passwordHash("h").role(Role.OWNER).active(true).build()));
        ApplicationRunner runner = bootstrap.backfillPlatformAdmin(users, platformAdmins, "owner");

        runner.run(mock(ApplicationArguments.class));

        var captor = org.mockito.ArgumentCaptor.forClass(PlatformAdmin.class);
        verify(platformAdmins).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("backfill is a no-op once platform_admin already has any row (never overrides a later, deliberate change)")
    void backfillNoOpWhenPlatformAdminAlreadyPopulated() throws Exception {
        when(platformAdmins.count()).thenReturn(1L);
        ApplicationRunner runner = bootstrap.backfillPlatformAdmin(users, platformAdmins, "owner");

        runner.run(mock(ApplicationArguments.class));

        verify(platformAdmins, never()).save(any());
        verifyNoInteractions(users);
    }

    @Test
    @DisplayName("backfill is a no-op when no account with that username exists yet")
    void backfillNoOpWhenUsernameNotFound() throws Exception {
        when(platformAdmins.count()).thenReturn(0L);
        when(users.findByUsername("owner")).thenReturn(Optional.empty());
        ApplicationRunner runner = bootstrap.backfillPlatformAdmin(users, platformAdmins, "owner");

        runner.run(mock(ApplicationArguments.class));

        verify(platformAdmins, never()).save(any());
    }
}
