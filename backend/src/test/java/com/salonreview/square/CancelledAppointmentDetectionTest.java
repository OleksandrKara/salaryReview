package com.salonreview.square;

import com.salonreview.repo.OwnerCustomerRepository;
import com.salonreview.square.SquareClient.AppointmentSegment;
import com.salonreview.square.SquareClient.Booking;
import com.salonreview.square.SquareClient.TeamMember;
import com.salonreview.square.SquareMonthAggregator.MonthAggregation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies which cancelled appointments the aggregator emits: only past, in-month,
 * CANCELLED_BY_SELLER bookings with provider + customer + service set. Customer-side cancellations
 * and future cancellations are excluded. Role filtering (owner/manager exclusion) is the service
 * layer's job — see {@link CancelledAppointmentServiceTest}.
 */
class CancelledAppointmentDetectionTest {

    private static final String TM   = "TM1";
    private static final String VAR  = "VAR1";
    private static final String CUST = "CUST1";

    private SquareClient square;
    private SquareMonthAggregator aggregator;

    @BeforeEach
    void setUp() {
        square = mock(SquareClient.class);
        OwnerCustomerRepository ownerRepo = mock(OwnerCustomerRepository.class);
        aggregator = new SquareMonthAggregator(square, new CashNoteParser(), ownerRepo);

        when(square.locationTimeZone()).thenReturn("UTC");
        when(square.allTeamMembers()).thenReturn(List.of(
                new TeamMember(TM, "Test", "Provider", "ACTIVE", false, null, null)));
        when(square.catalogPrices(any())).thenReturn(Map.of(VAR, new BigDecimal("80.00")));
        when(square.completedOrders(any(), any())).thenReturn(List.of());
        when(ownerRepo.findAll()).thenReturn(List.of());
    }

    private static Booking booking(String status, Instant start) {
        return new Booking("bk-1", status, start.toString(), null, null, "LOC", CUST,
                null, null, List.of(new AppointmentSegment(TM, VAR, 60)));
    }

    private MonthAggregation runFor(Booking b) {
        when(square.bookings(any(), any())).thenReturn(List.of(b));
        Instant t = Instant.parse(b.startAt());
        int year = t.atZone(java.time.ZoneOffset.UTC).getYear();
        int month = t.atZone(java.time.ZoneOffset.UTC).getMonthValue();
        return aggregator.aggregate(year, month, new BigDecimal("60.00"));
    }

    @Test
    @DisplayName("Past CANCELLED_BY_SELLER → emitted as a cancellation")
    void sellerCancelledPastIsEmitted() {
        MonthAggregation agg = runFor(booking("CANCELLED_BY_SELLER", Instant.now().minus(3, ChronoUnit.DAYS)));
        assertThat(agg.cancellations()).hasSize(1);
        var c = agg.cancellations().get(0);
        assertThat(c.bookingId()).isEqualTo("bk-1");
        assertThat(c.providerId()).isEqualTo(TM);
        assertThat(c.customerId()).isEqualTo(CUST);
        assertThat(c.gross()).isEqualByComparingTo("80.00");
        // A seller-cancelled booking is never also suspicious.
        assertThat(agg.suspicious()).isEmpty();
    }

    @Test
    @DisplayName("Customer-side cancellation → not emitted (not a provider action)")
    void customerCancelledNotEmitted() {
        MonthAggregation agg = runFor(booking("CANCELLED_BY_CUSTOMER", Instant.now().minus(3, ChronoUnit.DAYS)));
        assertThat(agg.cancellations()).isEmpty();
    }

    @Test
    @DisplayName("Future seller cancellation → not emitted (nothing happened yet)")
    void futureSellerCancelNotEmitted() {
        MonthAggregation agg = runFor(booking("CANCELLED_BY_SELLER", Instant.now().plus(3, ChronoUnit.DAYS)));
        assertThat(agg.cancellations()).isEmpty();
    }

    @Test
    @DisplayName("Accepted appointment → not a cancellation")
    void acceptedNotEmitted() {
        MonthAggregation agg = runFor(booking("ACCEPTED", Instant.now().minus(3, ChronoUnit.DAYS)));
        assertThat(agg.cancellations()).isEmpty();
    }
}
