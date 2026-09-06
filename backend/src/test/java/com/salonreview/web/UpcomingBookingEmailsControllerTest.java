package com.salonreview.web;

import com.salonreview.config.AppUserPrincipal;
import com.salonreview.domain.AppUser;
import com.salonreview.domain.Role;
import com.salonreview.domain.SquareBookingMirror;
import com.salonreview.repo.PlatformAdminRepository;
import com.salonreview.repo.SquareBookingMirrorRepository;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClientProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class UpcomingBookingEmailsControllerTest {

    private static final Long BUSINESS_ID = 1L;

    private SquareBookingMirrorRepository bookingMirrorRepository;
    private SquareClientProvider squareClientProvider;
    private SquareClient square;
    private PlatformAdminRepository platformAdmins;
    private UpcomingBookingEmailsController controller;

    private static AppUserPrincipal principal(Long userId) {
        return new AppUserPrincipal(AppUser.builder().id(userId).username("u" + userId)
                .passwordHash("h").role(Role.OWNER).active(true).build(), BUSINESS_ID);
    }

    @BeforeEach
    void setUp() {
        bookingMirrorRepository = mock(SquareBookingMirrorRepository.class);
        squareClientProvider = mock(SquareClientProvider.class);
        square = mock(SquareClient.class);
        platformAdmins = mock(PlatformAdminRepository.class);
        controller = new UpcomingBookingEmailsController(bookingMirrorRepository, squareClientProvider, platformAdmins);
        when(platformAdmins.existsById(1L)).thenReturn(true);
        when(squareClientProvider.forBusiness(BUSINESS_ID)).thenReturn(square);
    }

    private static SquareBookingMirror booking(String customerId, String status) {
        return SquareBookingMirror.builder().businessId(BUSINESS_ID).squareBookingId("bk-" + customerId)
                .squareCustomerId(customerId).status(status).startAt(Instant.now().plus(java.time.Duration.ofDays(3))).build();
    }

    @Test
    @DisplayName("resolves emails only for ACCEPTED upcoming bookings, dedupes repeat customers, skips those with no email")
    void resolvesEmailsForAcceptedUpcomingBookings() {
        when(bookingMirrorRepository.findByBusinessIdAndStartAtBetween(eq(BUSINESS_ID), any(), any())).thenReturn(List.of(
                booking("cust1", "ACCEPTED"),
                booking("cust1", "ACCEPTED"), // same customer, two bookings -> deduped
                booking("cust2", "ACCEPTED"),
                booking("cust3", "CANCELLED_BY_CUSTOMER") // not accepted -> excluded
        ));
        when(square.customerEmail("cust1")).thenReturn("jane@example.com");
        when(square.customerEmail("cust2")).thenReturn(null); // no email on file

        UpcomingBookingEmailsController.ResultDto result = controller.get(BUSINESS_ID, principal(1L));

        assertThat(result.upcomingCustomerCount()).isEqualTo(2); // cust1, cust2 (cust3 excluded, not ACCEPTED)
        assertThat(result.resolvedEmailCount()).isEqualTo(1);
        assertThat(result.emails()).containsExactly("jane@example.com");
    }

    @Test
    @DisplayName("non-platform-admin owner is rejected (403), no Square calls made")
    void nonPlatformAdminRejected() {
        when(platformAdmins.existsById(31L)).thenReturn(false);

        assertThatThrownBy(() -> controller.get(BUSINESS_ID, principal(31L)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Platform admin");

        verifyNoInteractions(squareClientProvider);
        verifyNoInteractions(bookingMirrorRepository);
    }
}
