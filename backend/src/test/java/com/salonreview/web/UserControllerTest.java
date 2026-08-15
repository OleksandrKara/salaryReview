package com.salonreview.web;

import com.salonreview.domain.AppUser;
import com.salonreview.domain.Role;
import com.salonreview.repo.AppUserRepository;
import com.salonreview.repo.BusinessMembershipRepository;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.repo.SopAcknowledgmentRepository;
import com.salonreview.service.ProviderDirectory;
import com.salonreview.square.SquareClientProvider;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserControllerTest {

    /** Regression: a manager who had acknowledged SOPs must delete cleanly — the sop_acknowledgments FK
     *  has no ON DELETE cascade, so the acknowledgments have to be removed before the user (deleting the
     *  user directly previously failed the constraint and surfaced as a 500). */
    @Test
    void deletingUserWithAcknowledgmentsClearsThemFirst() {
        AppUserRepository users = mock(AppUserRepository.class);
        SopAcknowledgmentRepository acks = mock(SopAcknowledgmentRepository.class);
        UserController controller = new UserController(users, mock(ProviderRepository.class),
                mock(ProviderDirectory.class), mock(SquareClientProvider.class), mock(PasswordEncoder.class), acks,
                mock(BusinessMembershipRepository.class), mock(com.salonreview.config.CurrentBusinessContext.class));

        AppUser manager = AppUser.builder().id(7L).username("m").role(Role.MANAGER).active(true).build();
        when(users.findById(7L)).thenReturn(Optional.of(manager));

        controller.delete(7L);

        InOrder ordered = inOrder(acks, users);
        ordered.verify(acks).deleteByUserId(7L); // acknowledgments cleared first (FK has no cascade)
        ordered.verify(users).delete(manager);
    }
}
