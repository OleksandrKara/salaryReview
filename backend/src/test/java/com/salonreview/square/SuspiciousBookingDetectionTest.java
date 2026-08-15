package com.salonreview.square;

import com.salonreview.domain.OwnerCustomer;
import com.salonreview.repo.OwnerCustomerRepository;
import com.salonreview.square.SquareClient.AppointmentSegment;
import com.salonreview.square.SquareClient.Booking;
import com.salonreview.square.SquareClient.Order;
import com.salonreview.square.SquareClient.OrderLineItem;
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
        com.salonreview.config.CurrentBusinessContext currentBusinessContext = mock(com.salonreview.config.CurrentBusinessContext.class);
        when(currentBusinessContext.id()).thenReturn(1L);
        SquareClientProvider squareClientProvider = mock(SquareClientProvider.class);
        when(squareClientProvider.forBusiness(1L)).thenReturn(square);
        ownerRepo = mock(OwnerCustomerRepository.class);
        aggregator = new SquareMonthAggregator(squareClientProvider, new CashNoteParser(), ownerRepo, currentBusinessContext);

        when(square.locationTimeZone()).thenReturn("UTC");
        when(square.allTeamMembers()).thenReturn(List.of(
                new TeamMember(TM, "Test", "Provider", "ACTIVE", false, null, null)));
        when(square.catalogPrices(any())).thenReturn(Map.of(VAR, new BigDecimal("80.00")));
        when(square.catalogNames(any())).thenReturn(Map.of(VAR, "Manicure"));
        when(square.completedOrders(any(), any())).thenReturn(List.of()); // no orders by default
        when(ownerRepo.findAllByBusinessId(1L)).thenReturn(List.of());
    }

    /** A booking 3 days ago — clearly in the past for any salon timezone. */
    private static Booking pastBooking(String customerId, String status, String sellerNote, String customerNote) {
        String pastIso = Instant.now().minus(3, ChronoUnit.DAYS).toString();
        return new Booking("bk-past", status, pastIso, null, null, "LOC", customerId,
                sellerNote, customerNote, List.of(new AppointmentSegment(TM, VAR, 60)));
    }

    /** A booking 3 days in the future. */
    private static Booking futureBooking(String customerId) {
        String futureIso = Instant.now().plus(3, ChronoUnit.DAYS).toString();
        return new Booking("bk-future", "ACCEPTED", futureIso, null, null, "LOC", customerId,
                null, null, List.of(new AppointmentSegment(TM, VAR, 60)));
    }

    /** A completed order for {@code customerId} whose line carries {@code catalogObjectId} (may differ
     *  from the booked SKU, or be null for a custom amount), closed at {@code when}. */
    private static Order paidOrder(String customerId, String catalogObjectId, Instant when) {
        SquareClient.Money gross = new SquareClient.Money(8000L, "USD");
        OrderLineItem line = new OrderLineItem("uid", "Some service", "1", catalogObjectId,
                gross, gross, gross, null);
        return new Order("ord-1", "LOC", customerId, "COMPLETED", when.toString(), when.toString(),
                List.of(line), null, null, null, null);
    }

    private MonthAggregation runForBookingMonth(List<Booking> bookings) {
        return runForBookingMonth(bookings, List.of());
    }

    private MonthAggregation runForBookingMonth(List<Booking> bookings, List<Order> orders) {
        when(square.bookings(any(), any())).thenReturn(bookings);
        when(square.completedOrders(any(), any())).thenReturn(orders);
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
        when(ownerRepo.findAllByBusinessId(1L)).thenReturn(List.of(oc));

        MonthAggregation agg = runForBookingMonth(List.of(pastBooking(CUST, "ACCEPTED", null, null)));

        assertThat(agg.suspicious()).isEmpty();
    }

    @Test
    @DisplayName("Future booking → not suspicious")
    void futureBookingExcluded() {
        MonthAggregation agg = runForBookingMonth(List.of(futureBooking(CUST)));
        assertThat(agg.suspicious()).isEmpty();
    }

    @Test
    @DisplayName("Customer paid a different SKU the same day → not suspicious (payment trail)")
    void sameDayPaymentDifferentSkuExcludes() {
        Booking booking = pastBooking(CUST, "ACCEPTED", null, null); // booked VAR
        Instant sameDay = Instant.parse(booking.startAt());
        // Order for the same customer, same day, but a DIFFERENT catalog SKU — the strict payout
        // matcher won't tie it to the booking, yet the visit clearly has a payment.
        MonthAggregation agg = runForBookingMonth(List.of(booking),
                List.of(paidOrder(CUST, "OTHER_VAR", sameDay)));
        assertThat(agg.suspicious()).isEmpty();
    }

    @Test
    @DisplayName("Customer paid a custom amount (no catalog id) the same day → not suspicious")
    void sameDayCustomAmountExcludes() {
        Booking booking = pastBooking(CUST, "ACCEPTED", null, null);
        Instant sameDay = Instant.parse(booking.startAt());
        MonthAggregation agg = runForBookingMonth(List.of(booking),
                List.of(paidOrder(CUST, null, sameDay)));
        assertThat(agg.suspicious()).isEmpty();
    }

    @Test
    @DisplayName("A different customer's payment nearby → still suspicious")
    void otherCustomerPaymentDoesNotExclude() {
        Booking booking = pastBooking(CUST, "ACCEPTED", null, null);
        Instant sameDay = Instant.parse(booking.startAt());
        MonthAggregation agg = runForBookingMonth(List.of(booking),
                List.of(paidOrder("SOMEONE_ELSE", "OTHER_VAR", sameDay)));
        assertThat(agg.suspicious()).hasSize(1);
    }

    @Test
    @DisplayName("Same customer's payment more than 2 days away → still suspicious")
    void distantPaymentDoesNotExclude() {
        Booking booking = pastBooking(CUST, "ACCEPTED", null, null);
        Instant fiveDaysBefore = Instant.parse(booking.startAt()).minus(5, ChronoUnit.DAYS);
        MonthAggregation agg = runForBookingMonth(List.of(booking),
                List.of(paidOrder(CUST, "OTHER_VAR", fiveDaysBefore)));
        assertThat(agg.suspicious()).hasSize(1);
    }
}
