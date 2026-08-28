package com.salonreview.square;

import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.repo.SquareBookingMirrorRepository;
import com.salonreview.repo.SquareOrderMirrorRepository;
import com.salonreview.repo.SquarePaymentMirrorRepository;
import com.salonreview.square.SquareClient.AppointmentSegment;
import com.salonreview.square.SquareClient.Booking;
import com.salonreview.square.SquareClient.Money;
import com.salonreview.square.SquareClient.Order;
import com.salonreview.square.SquareClient.OrderLineItem;
import com.salonreview.square.SquareClient.Payment;
import com.salonreview.square.SquareClient.Tender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SquareBookingMirrorIngestServiceTest {

    private SquareClient square;
    private SquareBookingMirrorRepository repository;
    private SquareOrderMirrorRepository orderRepository;
    private SquarePaymentMirrorRepository paymentRepository;
    private SquareBookingMirrorIngestService ingest;

    @BeforeEach
    void setUp() {
        square = mock(SquareClient.class);
        SquareClientProvider provider = mock(SquareClientProvider.class);
        when(provider.forBusiness(1L)).thenReturn(square);
        CurrentBusinessContext ctx = mock(CurrentBusinessContext.class);
        when(ctx.id()).thenReturn(1L);
        repository = mock(SquareBookingMirrorRepository.class);
        orderRepository = mock(SquareOrderMirrorRepository.class);
        paymentRepository = mock(SquarePaymentMirrorRepository.class);
        ingest = new SquareBookingMirrorIngestService(provider, repository, orderRepository, paymentRepository, ctx);
    }

    private static Booking booking(String id, String customerId) {
        return new Booking(id, "ACCEPTED", "2026-06-01T15:00:00Z", "2026-05-01T00:00:00Z",
                "2026-05-01T00:00:00Z", "LOC1", customerId, "cashew $80", null,
                List.of(new AppointmentSegment("TM1", "VAR1", 60)));
    }

    private static Order order(String id, String customerId) {
        Money price = new Money(10000L, "USD");
        OrderLineItem li = new OrderLineItem("li1", "Service", "1", "VAR1", price, price, price, null, null);
        return new Order(id, "LOC1", customerId, "COMPLETED", "2026-06-01T16:00:00Z", "2026-06-01T15:55:00Z",
                List.of(li), new Money(1500L, "USD"), null,
                List.of(new Tender("t1", "CARD", price)), null, null);
    }

    private static Payment payment(String id, String orderId, String customerId) {
        return new Payment(id, orderId, customerId, "COMPLETED", "2026-06-01T16:00:00Z",
                new Money(10000L, "USD"), new Money(1500L, "USD"));
    }

    @Test
    @DisplayName("ingestWindow upserts every booking from the location-wide call, never bookingsForCustomer")
    void ingestWindowUpsertsEveryBooking() {
        Instant from = Instant.parse("2026-06-01T00:00:00Z");
        Instant to = Instant.parse("2026-07-01T00:00:00Z");
        when(square.bookings(from, to)).thenReturn(List.of(booking("bk1", "CUST1"), booking("bk2", "CUST2")));

        int count = ingest.ingestWindow(from, to);

        assertThat(count).isEqualTo(2); // no orders/payments stubbed — default empty
        verify(repository).upsert(eq(1L), eq("bk1"), eq("CUST1"), eq("ACCEPTED"),
                any(), any(), any(), eq("LOC1"), eq("cashew $80"), eq(null), anyString());
        verify(repository).upsert(eq(1L), eq("bk2"), eq("CUST2"), eq("ACCEPTED"),
                any(), any(), any(), eq("LOC1"), eq("cashew $80"), eq(null), anyString());
        verify(square, times(0)).bookingsForCustomer(any(), any());
    }

    @Test
    @DisplayName("ingestWindow also upserts completed orders and payments in the same window")
    void ingestWindowUpsertsOrdersAndPayments() {
        Instant from = Instant.parse("2026-06-01T00:00:00Z");
        Instant to = Instant.parse("2026-07-01T00:00:00Z");
        when(square.bookings(from, to)).thenReturn(List.of());
        when(square.completedOrders(from, to)).thenReturn(List.of(order("ord1", "CUST1")));
        when(square.payments(from, to)).thenReturn(List.of(payment("pay1", "ord1", "CUST1")));

        int count = ingest.ingestWindow(from, to);

        assertThat(count).isEqualTo(2); // 1 order + 1 payment, 0 bookings
        verify(orderRepository).upsert(eq(1L), eq("ord1"), eq("CUST1"), eq("COMPLETED"),
                any(), any(), eq(new java.math.BigDecimal("15.00")), eq(java.math.BigDecimal.ZERO), anyString(), anyString());
        verify(paymentRepository).upsert(eq(1L), eq("pay1"), eq("ord1"), eq("CUST1"), eq("COMPLETED"),
                any(), eq(new java.math.BigDecimal("100.00")), eq(new java.math.BigDecimal("15.00")));
    }

    @Test
    @DisplayName("a booking with no appointment segments serializes to a null segments payload, not an error")
    void nullSegmentsHandledGracefully() {
        Instant from = Instant.parse("2026-06-01T00:00:00Z");
        Instant to = Instant.parse("2026-07-01T00:00:00Z");
        Booking noSegments = new Booking("bk3", "ACCEPTED", "2026-06-01T15:00:00Z", null, null,
                "LOC1", "CUST3", null, null, null);
        when(square.bookings(from, to)).thenReturn(List.of(noSegments));

        ingest.ingestWindow(from, to);

        verify(repository).upsert(eq(1L), eq("bk3"), eq("CUST3"), eq("ACCEPTED"),
                any(), eq(null), eq(null), eq("LOC1"), eq(null), eq(null), eq(null));
    }

    @Test
    @DisplayName("backfillHistory ingests multiple months and isolates one month's failure from the rest")
    void backfillHistoryIsolatesPerMonthFailures() {
        when(square.locationTimeZone()).thenReturn("UTC");
        when(square.bookings(any(), any()))
                .thenThrow(new RuntimeException("Square down"))
                .thenReturn(List.of(booking("bkOk", "CUSTOK")));

        ingest.backfillHistory(2);

        // Both months attempted despite the first throwing.
        verify(square, times(2)).bookings(any(), any());
        verify(repository).upsert(eq(1L), eq("bkOk"), eq("CUSTOK"), any(), any(), any(), any(),
                any(), any(), any(), anyString());
    }
}
