package com.salonreview.sms;

import com.salonreview.domain.Provider;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.square.SquareClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TechnicianNameResolverTest {

    private static final String CUSTOMER_ID = "cust1";

    private SquareClient square;
    private ProviderRepository providers;
    private TechnicianNameResolver resolver;
    private Instant asOf;

    @BeforeEach
    void setUp() {
        square = mock(SquareClient.class);
        providers = mock(ProviderRepository.class);
        resolver = new TechnicianNameResolver(square, providers);
        asOf = Instant.parse("2026-08-05T18:00:00Z");
    }

    private static SquareClient.Booking booking(String startAt, String status, String teamMemberId) {
        List<SquareClient.AppointmentSegment> segments = teamMemberId == null
                ? List.of() : List.of(new SquareClient.AppointmentSegment(teamMemberId, "var1", 60));
        return new SquareClient.Booking("bk1", status, startAt, null, null, "loc1", CUSTOMER_ID, null, null, segments);
    }

    @Test
    @DisplayName("most recent past booking's team member maps to a known provider → returns display name")
    void resolvesDisplayNameForKnownTechnician() {
        when(square.bookingsForCustomer(eq(CUSTOMER_ID), any()))
                .thenReturn(List.of(booking("2026-08-05T17:00:00Z", "ACCEPTED", "TM1")));
        Provider provider = Provider.builder().id(1L).name("Susan").displayName("Susan").build();
        when(providers.findBySquareTeamMemberId("TM1")).thenReturn(Optional.of(provider));

        assertThat(resolver.resolveForCustomer(CUSTOMER_ID, asOf)).contains("Susan");
    }

    @Test
    @DisplayName("no bookings at all → empty")
    void noBookingsResolvesEmpty() {
        when(square.bookingsForCustomer(eq(CUSTOMER_ID), any())).thenReturn(List.of());

        assertThat(resolver.resolveForCustomer(CUSTOMER_ID, asOf)).isEmpty();
    }

    @Test
    @DisplayName("only a future booking (visit hasn't happened yet relative to asOf) → empty")
    void onlyFutureBookingResolvesEmpty() {
        when(square.bookingsForCustomer(eq(CUSTOMER_ID), any()))
                .thenReturn(List.of(booking("2026-08-06T17:00:00Z", "ACCEPTED", "TM1")));

        assertThat(resolver.resolveForCustomer(CUSTOMER_ID, asOf)).isEmpty();
    }

    @Test
    @DisplayName("most recent past booking was cancelled → empty, doesn't fall through to an older one's technician")
    void cancelledBookingIsExcluded() {
        when(square.bookingsForCustomer(eq(CUSTOMER_ID), any()))
                .thenReturn(List.of(booking("2026-08-05T17:00:00Z", "CANCELLED_BY_CUSTOMER", "TM1")));

        assertThat(resolver.resolveForCustomer(CUSTOMER_ID, asOf)).isEmpty();
    }

    @Test
    @DisplayName("booking has no team member on its segments → empty")
    void noTeamMemberOnSegmentsResolvesEmpty() {
        when(square.bookingsForCustomer(eq(CUSTOMER_ID), any()))
                .thenReturn(List.of(booking("2026-08-05T17:00:00Z", "ACCEPTED", null)));

        assertThat(resolver.resolveForCustomer(CUSTOMER_ID, asOf)).isEmpty();
    }

    @Test
    @DisplayName("team member id has no mapped Provider yet → empty, not an exception")
    void unmappedTeamMemberResolvesEmpty() {
        when(square.bookingsForCustomer(eq(CUSTOMER_ID), any()))
                .thenReturn(List.of(booking("2026-08-05T17:00:00Z", "ACCEPTED", "TM_UNKNOWN")));
        when(providers.findBySquareTeamMemberId("TM_UNKNOWN")).thenReturn(Optional.empty());

        assertThat(resolver.resolveForCustomer(CUSTOMER_ID, asOf)).isEmpty();
    }

    @Test
    @DisplayName("Square lookup throws → empty, never propagates (best-effort resolution)")
    void squareFailureResolvesEmpty() {
        when(square.bookingsForCustomer(eq(CUSTOMER_ID), any())).thenThrow(new RuntimeException("Square down"));

        assertThat(resolver.resolveForCustomer(CUSTOMER_ID, asOf)).isEmpty();
    }

    @Test
    @DisplayName("null/blank customer id → empty without calling Square at all")
    void nullCustomerIdResolvesEmpty() {
        assertThat(resolver.resolveForCustomer(null, asOf)).isEmpty();
        assertThat(resolver.resolveForCustomer("", asOf)).isEmpty();
    }
}
