package com.salonreview.intake;

import com.salonreview.config.AppUserPrincipal;
import com.salonreview.domain.IntakeForm;
import com.salonreview.domain.Role;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IntakeStaffServiceTest {

    AppUserPrincipal who(Role role, Long providerId) {
        AppUserPrincipal p = mock(AppUserPrincipal.class);
        when(p.getRole()).thenReturn(role);
        when(p.getProviderId()).thenReturn(providerId);
        return p;
    }

    IntakeForm form(Long business, Long provider) {
        return IntakeForm.builder().id(1L).businessId(business).providerId(provider).squareCustomerId("C").squareBookingId("B")
                .status(IntakeForm.SIGNED).appointmentStartAt(Instant.now()).build();
    }

    @Test
    void ownerAndManagerSeeAllInTheirBusiness() {
        assertThat(IntakeStaffService.canSee(who(Role.OWNER, null), form(1L, 5L), 1L)).isTrue();
        assertThat(IntakeStaffService.canSee(who(Role.MANAGER, null), form(1L, null), 1L)).isTrue();
    }

    @Test
    void providerSeesOnlyHerOwn() {
        assertThat(IntakeStaffService.canSee(who(Role.PROVIDER, 5L), form(1L, 5L), 1L)).isTrue();
        assertThat(IntakeStaffService.canSee(who(Role.PROVIDER, 5L), form(1L, 6L), 1L)).isFalse();
        assertThat(IntakeStaffService.canSee(who(Role.PROVIDER, 5L), form(1L, null), 1L)).isFalse();
        assertThat(IntakeStaffService.canSee(who(Role.PROVIDER, null), form(1L, null), 1L)).isFalse();
    }

    @Test
    void crossBusinessAlwaysDenied() {
        assertThat(IntakeStaffService.canSee(who(Role.OWNER, null), form(2L, 5L), 1L)).isFalse();
        assertThat(IntakeStaffService.canSee(who(Role.PROVIDER, 5L), form(2L, 5L), 1L)).isFalse();
    }

    @Test
    void otherRolesDenied() {
        assertThat(IntakeStaffService.canSee(who(Role.ADS_MANAGER, null), form(1L, 5L), 1L)).isFalse();
    }
}
