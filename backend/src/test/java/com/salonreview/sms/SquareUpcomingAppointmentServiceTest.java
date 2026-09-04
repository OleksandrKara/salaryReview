package com.salonreview.sms;

import com.salonreview.square.SquareClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Direct tests for the phone-number-scoped upcoming-appointment check extracted 2026-09-04 after
 * a real production bug — see this class's own doc comment for the full story. Its callers
 * ({@code LapsedCustomerWinbackScheduler} etc.) already cover the "does this affect a real send"
 * end-to-end behavior; these tests focus on the phone-number fan-out logic itself in isolation.
 */
class SquareUpcomingAppointmentServiceTest {

    private static final String PHONE = "+15551234567";

    private final SquareUpcomingAppointmentService service = new SquareUpcomingAppointmentService();

    private static SquareClient.Booking booking(String customerId, String status, String startAt) {
        return new SquareClient.Booking("bk1", status, startAt, null, null, null, customerId, null, null, null);
    }

    @Test
    @DisplayName("no Square profiles for the phone number → false, no booking calls made")
    void noProfilesForPhoneReturnsFalse() {
        SquareClient square = mock(SquareClient.class);
        when(square.customerIdsForPhone(PHONE)).thenReturn(List.of());

        assertThat(service.hasUpcomingAppointment(PHONE, square)).isFalse();
    }

    @Test
    @DisplayName("the one profile for the phone number has an upcoming booking → true")
    void singleProfileWithUpcomingBookingReturnsTrue() {
        SquareClient square = mock(SquareClient.class);
        when(square.customerIdsForPhone(PHONE)).thenReturn(List.of("cust1"));
        String futureIso = Instant.now().plusSeconds(3600).toString();
        when(square.bookingsForCustomer(eq("cust1"), any())).thenReturn(List.of(booking("cust1", "ACCEPTED", futureIso)));

        assertThat(service.hasUpcomingAppointment(PHONE, square)).isTrue();
    }

    @Test
    @DisplayName("the real bug: first profile has no bookings, a *second* profile sharing the same "
            + "phone number has an upcoming one → true, not missed")
    void secondProfileWithUpcomingBookingIsNotMissed() {
        SquareClient square = mock(SquareClient.class);
        when(square.customerIdsForPhone(PHONE)).thenReturn(List.of("cust1", "cust1-sibling"));
        when(square.bookingsForCustomer(eq("cust1"), any())).thenReturn(List.of());
        String futureIso = Instant.now().plusSeconds(3600).toString();
        when(square.bookingsForCustomer(eq("cust1-sibling"), any()))
                .thenReturn(List.of(booking("cust1-sibling", "ACCEPTED", futureIso)));

        assertThat(service.hasUpcomingAppointment(PHONE, square)).isTrue();
    }

    @Test
    @DisplayName("every profile for the phone number has only cancelled/past bookings → false")
    void noRealUpcomingBookingAcrossAnyProfileReturnsFalse() {
        SquareClient square = mock(SquareClient.class);
        when(square.customerIdsForPhone(PHONE)).thenReturn(List.of("cust1", "cust1-sibling"));
        String futureIso = Instant.now().plusSeconds(3600).toString();
        // Genuinely past (yesterday), not just "an hour ago" — isTodayOrLater compares calendar
        // dates, and "an hour ago" is still today.
        String pastIso = Instant.now().minus(2, ChronoUnit.DAYS).toString();
        when(square.bookingsForCustomer(eq("cust1"), any()))
                .thenReturn(List.of(booking("cust1", "CANCELLED_BY_CUSTOMER", futureIso)));
        when(square.bookingsForCustomer(eq("cust1-sibling"), any()))
                .thenReturn(List.of(booking("cust1-sibling", "ACCEPTED", pastIso)));

        assertThat(service.hasUpcomingAppointment(PHONE, square)).isFalse();
    }

    @Test
    @DisplayName("null/blank phone number → false, never calls Square")
    void blankPhoneNumberReturnsFalseWithoutCallingSquare() {
        SquareClient square = mock(SquareClient.class);

        assertThat(service.hasUpcomingAppointment(null, square)).isFalse();
        assertThat(service.hasUpcomingAppointment("  ", square)).isFalse();
        org.mockito.Mockito.verifyNoInteractions(square);
    }
}
