package com.salonreview.web;

import com.salonreview.config.AppUserPrincipal;
import com.salonreview.domain.AppUser;
import com.salonreview.domain.Business;
import com.salonreview.domain.Role;
import com.salonreview.repo.PlatformAdminRepository;
import com.salonreview.service.BusinessProvisioningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Phase 5.2 (design.md D4): every business's owner used to be able to list/create businesses via
 * these endpoints — only a real platform_admin row should be able to now. See V107's own migration
 * comment for the live gap this closes.
 */
class PlatformBusinessControllerTest {

    private BusinessProvisioningService service;
    private PlatformAdminRepository platformAdmins;
    private PlatformBusinessController controller;

    private static AppUserPrincipal principal(Long userId) {
        return new AppUserPrincipal(AppUser.builder().id(userId).username("u" + userId)
                .passwordHash("h").role(Role.OWNER).active(true).build(), 1L);
    }

    @BeforeEach
    void setUp() {
        service = mock(BusinessProvisioningService.class);
        platformAdmins = mock(PlatformAdminRepository.class);
        controller = new PlatformBusinessController(service, platformAdmins);
    }

    @Test
    @DisplayName("a platform_admin can list businesses")
    void platformAdminCanList() {
        when(platformAdmins.existsById(1L)).thenReturn(true);
        when(service.list()).thenReturn(List.of(
                Business.builder().id(1L).name("AK.LUX.NAILS").shortCode("akluxnails")
                        .timezone("America/Los_Angeles").active(true).createdAt(Instant.now()).build()));

        List<PlatformBusinessController.BusinessDto> result = controller.list(principal(1L));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("AK.LUX.NAILS");
    }

    @Test
    @DisplayName("an OWNER of some business who is NOT a platform_admin is rejected (403), service never called")
    void nonPlatformAdminOwnerRejectedFromList() {
        when(platformAdmins.existsById(31L)).thenReturn(false);

        assertThatThrownBy(() -> controller.list(principal(31L)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Platform admin");

        verify(service, never()).list();
    }

    @Test
    @DisplayName("an OWNER of some business who is NOT a platform_admin cannot create a new business either")
    void nonPlatformAdminOwnerRejectedFromCreate() {
        when(platformAdmins.existsById(31L)).thenReturn(false);
        var body = new PlatformBusinessController.CreateBusinessRequest("New Salon", "newsalon",
                "America/Los_Angeles", "newowner", "pw");

        assertThatThrownBy(() -> controller.create(body, principal(31L)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Platform admin");

        verify(service, never()).create(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a platform_admin can create a new business")
    void platformAdminCanCreate() {
        when(platformAdmins.existsById(1L)).thenReturn(true);
        Business created = Business.builder().id(3L).name("New Salon").shortCode("newsalon")
                .timezone("America/Los_Angeles").active(true).createdAt(Instant.now()).build();
        when(service.create("New Salon", "newsalon", "America/Los_Angeles", "newowner", "pw"))
                .thenReturn(created);
        var body = new PlatformBusinessController.CreateBusinessRequest("New Salon", "newsalon",
                "America/Los_Angeles", "newowner", "pw");

        var response = controller.create(body, principal(1L));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody().name()).isEqualTo("New Salon");
    }
}
