package com.salonreview.web;

import com.salonreview.config.AppUserPrincipal;
import com.salonreview.config.CurrentBusinessContextFilter;
import com.salonreview.domain.AppUser;
import com.salonreview.domain.Business;
import com.salonreview.domain.Role;
import com.salonreview.repo.BusinessMembershipRepository;
import com.salonreview.repo.BusinessRepository;
import com.salonreview.repo.PlatformAdminRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** Phase 6.1/6.2 (design.md D12). */
class BusinessSwitchControllerTest {

    private BusinessRepository businesses;
    private BusinessMembershipRepository memberships;
    private PlatformAdminRepository platformAdmins;
    private BusinessSwitchController controller;
    private HttpServletRequest request;
    private HttpSession session;

    private static AppUserPrincipal principal(Long userId) {
        return new AppUserPrincipal(AppUser.builder().id(userId).username("u" + userId)
                .passwordHash("h").role(Role.OWNER).active(true).build(), 1L);
    }

    @BeforeEach
    void setUp() {
        businesses = mock(BusinessRepository.class);
        memberships = mock(BusinessMembershipRepository.class);
        platformAdmins = mock(PlatformAdminRepository.class);
        controller = new BusinessSwitchController(businesses, memberships, platformAdmins);
        request = mock(HttpServletRequest.class);
        session = mock(HttpSession.class);
        when(request.getSession(true)).thenReturn(session);
    }

    @Test
    @DisplayName("platform_admin can switch to any active business regardless of membership rows")
    void platformAdminCanSwitchToAnyActiveBusiness() {
        when(businesses.findById(2L)).thenReturn(Optional.of(
                Business.builder().id(2L).name("AK PMU").active(true).build()));
        when(platformAdmins.existsById(1L)).thenReturn(true);

        var result = controller.switchBusiness(new BusinessSwitchController.SwitchRequest(2L), principal(1L), request);

        assertThat(result.get("businessId")).isEqualTo(2L);
        verify(session).setAttribute(CurrentBusinessContextFilter.ACTIVE_BUSINESS_SESSION_ATTR, 2L);
        verify(memberships, never()).existsByUserIdAndBusinessId(anyLong(), anyLong());
    }

    @Test
    @DisplayName("a non-platform-admin can switch to a business they have a real membership row for")
    void nonAdminCanSwitchToOwnMembership() {
        when(businesses.findById(2L)).thenReturn(Optional.of(
                Business.builder().id(2L).name("AK PMU").active(true).build()));
        when(platformAdmins.existsById(31L)).thenReturn(false);
        when(memberships.existsByUserIdAndBusinessId(31L, 2L)).thenReturn(true);

        var result = controller.switchBusiness(new BusinessSwitchController.SwitchRequest(2L), principal(31L), request);

        assertThat(result.get("businessId")).isEqualTo(2L);
        verify(session).setAttribute(CurrentBusinessContextFilter.ACTIVE_BUSINESS_SESSION_ATTR, 2L);
    }

    @Test
    @DisplayName("2026-08-18: a non-platform-admin with no membership for the target business is rejected (403)")
    void nonAdminRejectedWithoutMembership() {
        when(businesses.findById(2L)).thenReturn(Optional.of(
                Business.builder().id(2L).name("AK PMU").active(true).build()));
        when(platformAdmins.existsById(31L)).thenReturn(false);
        when(memberships.existsByUserIdAndBusinessId(31L, 2L)).thenReturn(false);

        assertThatThrownBy(() -> controller.switchBusiness(
                new BusinessSwitchController.SwitchRequest(2L), principal(31L), request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("No access");

        verify(session, never()).setAttribute(anyString(), any());
    }

    @Test
    @DisplayName("switching to a nonexistent business id is rejected (404), not silently accepted")
    void switchRejectsNonexistentBusiness() {
        when(businesses.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.switchBusiness(
                new BusinessSwitchController.SwitchRequest(99L), principal(1L), request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("No such business");

        verify(session, never()).setAttribute(anyString(), any());
    }

    @Test
    @DisplayName("switching to an inactive (suspended) business is rejected, even for a platform_admin")
    void switchRejectsInactiveBusiness() {
        when(businesses.findById(2L)).thenReturn(Optional.of(
                Business.builder().id(2L).name("AK PMU").active(false).build()));

        assertThatThrownBy(() -> controller.switchBusiness(
                new BusinessSwitchController.SwitchRequest(2L), principal(1L), request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("No such business");
    }
}
