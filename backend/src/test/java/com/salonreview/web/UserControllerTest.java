package com.salonreview.web;

import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.domain.AppUser;
import com.salonreview.domain.Role;
import com.salonreview.repo.AppUserRepository;
import com.salonreview.repo.BusinessMembershipRepository;
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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class UserControllerTest {

    private static final Long BUSINESS_A = 1L;

    private AppUserRepository users;
    private ProviderRepository providers;
    private SopAcknowledgmentRepository acks;
    private CurrentBusinessContext currentBusinessContext;
    private UserController controller;

    @BeforeEach
    void setUp() {
        users = mock(AppUserRepository.class);
        providers = mock(ProviderRepository.class);
        acks = mock(SopAcknowledgmentRepository.class);
        currentBusinessContext = mock(CurrentBusinessContext.class);
        when(currentBusinessContext.id()).thenReturn(BUSINESS_A);
        controller = new UserController(users, providers, mock(ProviderDirectory.class),
                mock(SquareClientProvider.class), mock(PasswordEncoder.class), acks,
                mock(BusinessMembershipRepository.class), currentBusinessContext);
    }

    /** Regression: a manager who had acknowledged SOPs must delete cleanly — the sop_acknowledgments FK
     *  has no ON DELETE cascade, so the acknowledgments have to be removed before the user (deleting the
     *  user directly previously failed the constraint and surfaced as a 500). */
    @Test
    void deletingUserWithAcknowledgmentsClearsThemFirst() {
        AppUser manager = AppUser.builder().id(7L).username("m").role(Role.MANAGER).active(true).build();
        when(users.findByIdAndBusinessId(7L, BUSINESS_A)).thenReturn(Optional.of(manager));

        controller.delete(7L);

        InOrder ordered = inOrder(acks, users);
        ordered.verify(acks).deleteByUserId(7L); // acknowledgments cleared first (FK has no cascade)
        ordered.verify(users).delete(manager);
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
