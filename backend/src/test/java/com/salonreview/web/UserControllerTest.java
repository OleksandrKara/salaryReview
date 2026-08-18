package com.salonreview.web;

import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.domain.AppUser;
import com.salonreview.domain.Role;
import com.salonreview.repo.AppUserRepository;
import com.salonreview.repo.BusinessMembershipRepository;
import com.salonreview.repo.PlatformAdminRepository;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.repo.SopAcknowledgmentRepository;
import com.salonreview.service.ProviderDirectory;
import com.salonreview.square.SquareClientProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class UserControllerTest {

    private static final Long BUSINESS_A = 1L;

    private AppUserRepository users;
    private ProviderRepository providers;
    private SopAcknowledgmentRepository acks;
    private BusinessMembershipRepository memberships;
    private PlatformAdminRepository platformAdmins;
    private CurrentBusinessContext currentBusinessContext;
    private UserController controller;

    @BeforeEach
    void setUp() {
        users = mock(AppUserRepository.class);
        providers = mock(ProviderRepository.class);
        acks = mock(SopAcknowledgmentRepository.class);
        memberships = mock(BusinessMembershipRepository.class);
        platformAdmins = mock(PlatformAdminRepository.class);
        currentBusinessContext = mock(CurrentBusinessContext.class);
        when(currentBusinessContext.id()).thenReturn(BUSINESS_A);
        controller = new UserController(users, providers, mock(ProviderDirectory.class),
                mock(SquareClientProvider.class), mock(PasswordEncoder.class), acks,
                memberships, platformAdmins, currentBusinessContext);
    }

    /** Regression: a manager who had acknowledged SOPs must delete cleanly — the sop_acknowledgments FK
     *  has no ON DELETE cascade, so the acknowledgments have to be removed before the user (deleting the
     *  user directly previously failed the constraint and surfaced as a 500). */
    @Test
    void deletingUserWithAcknowledgmentsClearsThemFirst() {
        AppUser manager = AppUser.builder().id(7L).username("m").role(Role.MANAGER).active(true).build();
        when(users.findByIdAndBusinessId(7L, BUSINESS_A)).thenReturn(Optional.of(manager));

        controller.delete(7L);

        InOrder ordered = inOrder(acks, memberships, platformAdmins, users);
        ordered.verify(acks).deleteByUserId(7L); // acknowledgments cleared first (FK has no cascade)
        ordered.verify(memberships).deleteByUserId(7L);
        ordered.verify(platformAdmins).deleteByUserId(7L);
        ordered.verify(users).delete(manager);
    }

    /** 2026-08-18 live incident: reported by the owner as a raw 500 ("update or delete on table
     * app_user violates foreign key constraint business_membership_user_id_fkey") — business_membership
     * (added in Phase 1) was never wired into delete()'s FK cleanup the way sop_acknowledgments was.
     * platform_admin has the identical shape (a user holds at most one row), fixed in the same change. */
    @Test
    @DisplayName("2026-08-18 live incident: delete() clears business_membership and platform_admin "
            + "before deleting the user, not just sop_acknowledgments")
    void deletingUserClearsMembershipAndPlatformAdminFirst() {
        AppUser manager = AppUser.builder().id(32L).username("staff").role(Role.MANAGER).active(true).build();
        when(users.findByIdAndBusinessId(32L, BUSINESS_A)).thenReturn(Optional.of(manager));

        controller.delete(32L);

        verify(memberships).deleteByUserId(32L);
        verify(platformAdmins).deleteByUserId(32L);
    }

    @Test
    @DisplayName("2026-08-18 live vulnerability: DELETE cannot reach another business's user by id — "
            + "was users.findById(id), business-unscoped")
    void deleteRejectsAnotherBusinesssUserId() {
        when(users.findByIdAndBusinessId(31L, BUSINESS_A)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.delete(31L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("No such user");

        verify(users, never()).delete(any());
    }

    @Test
    @DisplayName("2026-08-18 live vulnerability: PATCH cannot reach another business's user by id — "
            + "was users.findById(id), business-unscoped (could reset their password/role/active status)")
    void updateRejectsAnotherBusinesssUserId() {
        when(users.findByIdAndBusinessId(31L, BUSINESS_A)).thenReturn(Optional.empty());
        var req = new UserController.UpdateRequest(null, null, null, null);

        assertThatThrownBy(() -> controller.update(31L, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("No such user");

        verify(users, never()).save(any());
    }

    @Test
    @DisplayName("role editing now exposed in the UI: PATCH rejects demoting the last active owner "
            + "away from OWNER, same guarantee delete() already had")
    void updateRejectsDemotingLastActiveOwner() {
        AppUser owner = AppUser.builder().id(5L).username("owner").role(Role.OWNER).active(true).build();
        when(users.findByIdAndBusinessId(5L, BUSINESS_A)).thenReturn(Optional.of(owner));
        when(users.findAllByBusinessIdOrderByUsernameAsc(BUSINESS_A)).thenReturn(java.util.List.of(owner));
        var req = new UserController.UpdateRequest(Role.MANAGER, null, null, null);

        assertThatThrownBy(() -> controller.update(5L, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("last active owner");

        verify(users, never()).save(any());
    }

    @Test
    @DisplayName("role editing: promoting a MANAGER to OWNER is allowed, and the guard doesn't fire "
            + "when the target role stays OWNER")
    void updateAllowsPromotingToOwner() {
        AppUser manager = AppUser.builder().id(6L).username("m2").role(Role.MANAGER).active(true).build();
        when(users.findByIdAndBusinessId(6L, BUSINESS_A)).thenReturn(Optional.of(manager));
        when(users.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var req = new UserController.UpdateRequest(Role.OWNER, null, null, null);

        UserController.UserView result = controller.update(6L, req);

        assertThat(result.role()).isEqualTo(Role.OWNER);
    }

    @Test
    @DisplayName("2026-08-18 live vulnerability: creating a PROVIDER account cannot link another "
            + "business's provider — was providers.existsById(providerId), business-unscoped")
    void createRejectsAnotherBusinesssProviderLink() {
        when(users.existsByBusinessIdAndUsername(BUSINESS_A, "newtech")).thenReturn(false);
        when(providers.existsByIdAndBusinessId(99L, BUSINESS_A)).thenReturn(false);
        var req = new UserController.CreateRequest("newtech", "pw", Role.PROVIDER, 99L, null, null, null);

        assertThatThrownBy(() -> controller.create(req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("No such provider");

        verify(users, never()).save(any());
    }
}
