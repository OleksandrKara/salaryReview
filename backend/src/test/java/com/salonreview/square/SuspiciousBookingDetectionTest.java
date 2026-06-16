package com.salonreview.square;

import com.salonreview.domain.OwnerCustomer;
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
 * Verifies the exclusion rules for suspicious-booking detection inside
 * {@link SquareMonthAggregator}: past + in-status + no matched order + no cash note + non-owner
 * customer is the only path that flags a booking; everything else is excluded.
 */
class SuspiciousBookingDetectionTest {

    private static final String TM   = "TM1";
    private static final String VAR  = "VAR1";
    private static final String CUST = "CUST1";

    private SquareClient square;
    private OwnerCustomerRepository ownerRepo;
    private SquareMonthAggregator aggregator;

    @BeforeEach
    void setUp() {
        square = mock(SquareClient.class);
        ownerRepo = mock(OwnerCustomerRepository.class);
        aggregator = new SquareMonthAggregator(square, new CashNoteParser(), ownerRepo);

        when(square.locationTimeZone()).thenReturn("UTC");
        when(square.allTeamMembers()).thenReturn(List.of(
                new TeamMember(TM, "Test", "Provider", "ACTIVE", false, null, null)));
        when(square.catalogPrices(any())).thenReturn(Map.of(VAR, new BigDecimal("80.00")));
        when(square.catalogNames(any())).thenReturn(Map.of(VAR, "Manicure"));
        when(square.completedOrders(any(), any())).thenReturn(List.of()); // no orders by default
        when(ownerRepo.findAll()).thenReturn(List.of());
    }

    /** A booking 3 days ago — clearly in the past for any salon timezone. */
    private static Booking pastBooking(String customerId, String status, String sellerNote, String customerNote) {
        String pastIso = Instant.now().minus(3, ChronoUnit.DAYS).toString();
        return new Booking("bk-past", status, pastIso, null, "LOC", customerId,
                sellerNote, customerNote, List.of(new AppointmentSegment(TM, VAR, 60)));
    }

    /** A booking 3 days in the future. */
    private static Booking futureBooking(String customerId) {
        String futureIso = Instant.now().plus(3, ChronoUnit.DAYS).toString();
        return new Booking("bk-future", "ACCEPTED", futureIso, null, "LOC", customerId,
                null, null, List.of(new AppointmentSegment(TM, VAR, 60)));
    }

    private MonthAggregation runForBookingMonth(List<Booking> bookings) {
        when(square.bookings(any(), any())).thenReturn(bookings);
        // Run against the booking's actual month — pull from the first one.
        Instant t = Instant.parse(bookings.get(0).startAt());
        int year = t.atZone(java.time.ZoneOffset.UTC).getYear();
        int month = t.atZone(java.time.ZoneOffset.UTC).getMonthValue();
        return aggregator.aggregate(year, month, new BigDecimal("60.00"));
    }

    @Test
    @DisplayName("Past + ACCEPTED + no order + no note + non-owner → SUSPICIOUS")
    void flagsCleanPastBookingWithNoTrail() {
        MonthAggregation agg = runForBookingMonth(List.of(pastBooking(CUST, "ACCEPTED", null, null)));

        assertThat(agg.suspicious()).hasSize(1);
        var c = agg.suspicious().get(0);
        assertThat(c.bookingId()).isEqualTo("bk-past");
        assertThat(c.providerId()).isEqualTo(TM);
        assertThat(c.customerId()).isEqualTo(CUST);
        assertThat(c.gross()).isEqualByComparingTo("80.00");
    }

    @Test
    @DisplayName("CANCELLED_BY_CUSTOMER → not suspicious")
    void cancelledIsNotSuspicious() {
        MonthAggregation agg = runForBookingMonth(List.of(pastBooking(CUST, "CANCELLED_BY_CUSTOMER", null, null)));
        assertThat(agg.suspicious()).isEmpty();
    }

    @Test
    @DisplayName("NO_SHOW → not suspicious")
    void noShowIsNotSuspicious() {
        MonthAggregation agg = runForBookingMonth(List.of(pastBooking(CUST, "NO_SHOW", null, null)));
        assertThat(agg.suspicious()).isEmpty();
    }

    @Test
    @DisplayName("Cash note in seller note → not suspicious")
    void cashNoteInSellerNoteExcludes() {
        MonthAggregation agg = runForBookingMonth(List.of(pastBooking(CUST, "ACCEPTED", "cashew $80", null)));
        assertThat(agg.suspicious()).isEmpty();
    }

    @Test
    @DisplayName("Cash note in customer note → not suspicious")
    void cashNoteInCustomerNoteExcludes() {
        MonthAggregation agg = runForBookingMonth(List.of(pastBooking(CUST, "ACCEPTED", null, "наличные $80")));
        assertThat(agg.suspicious()).isEmpty();
    }

    @Test
    @DisplayName("Owner customer → not suspicious")
    void ownerCustomerExcludes() {
        OwnerCustomer oc = new OwnerCustomer();
        oc.setId(1L);
        oc.setSquareCustomerId(CUST);
        when(ownerRepo.findAll()).thenReturn(List.of(oc));

        MonthAggregation agg = runForBookingMonth(List.of(pastBooking(CUST, "ACCEPTED", null, null)));

        assertThat(agg.suspicious()).isEmpty();
    }

    @Test
    @DisplayName("Future booking → not suspicious")
    void futureBookingExcluded() {
        MonthAggregation agg = runForBookingMonth(List.of(futureBooking(CUST)));
        assertThat(agg.suspicious()).isEmpty();
    }
}
