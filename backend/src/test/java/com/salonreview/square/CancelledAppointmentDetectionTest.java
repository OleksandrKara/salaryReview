package com.salonreview.square;

import com.salonreview.repo.OwnerCustomerRepository;
import com.salonreview.repo.SalonConfigRepository;
import com.salonreview.square.SquareClient.AppointmentSegment;
import com.salonreview.square.SquareClient.Booking;
import com.salonreview.square.SquareClient.Money;
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
 * Verifies which cancelled appointments the aggregator emits: only in-month CANCELLED_BY_SELLER
 * bookings cancelled AFTER their start time (the "slot happened, then voided" pattern), with
 * provider + customer + service set, and with no cancellation fee charged. Customer-side cancels,
 * advance cancels, and fee-charged cancels are excluded. Role filtering (owner/manager exclusion) is
 * the service layer's job — see {@link CancelledAppointmentServiceTest}.
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
        com.salonreview.config.CurrentBusinessContext currentBusinessContext = mock(com.salonreview.config.CurrentBusinessContext.class);
        when(currentBusinessContext.id()).thenReturn(1L);
        SquareClientProvider squareClientProvider = mock(SquareClientProvider.class);
        when(squareClientProvider.forBusiness(1L)).thenReturn(square);
        OwnerCustomerRepository ownerRepo = mock(OwnerCustomerRepository.class);
        SalonConfigRepository salonConfigRepo = mock(SalonConfigRepository.class);
        // Phase 4.4: this test's own "fee charged -> not emitted" case relies on cancellation-fee
        // detection actually being on for this business — matches Business A's real $25 value.
        when(salonConfigRepo.findByBusinessId(1L)).thenReturn(java.util.Optional.of(
                com.salonreview.domain.SalonConfig.builder().businessId(1L)
                        .noShowFeeAmount(new BigDecimal("25.00")).build()));
        aggregator = new SquareMonthAggregator(squareClientProvider, new CashNoteParser(), ownerRepo, currentBusinessContext, salonConfigRepo,
                mock(com.salonreview.repo.SquareBookingMirrorRepository.class), mock(com.salonreview.repo.SquareOrderMirrorRepository.class),
                mock(com.salonreview.repo.SquarePaymentMirrorRepository.class));

        when(square.locationTimeZone()).thenReturn("UTC");
        when(square.allTeamMembers()).thenReturn(List.of(
                new TeamMember(TM, "Test", "Provider", "ACTIVE", false, null, null)));
        when(square.catalogPrices(any())).thenReturn(Map.of(VAR, new BigDecimal("80.00")));
        when(square.completedOrders(any(), any())).thenReturn(List.of());
        when(ownerRepo.findAllByBusinessId(1L)).thenReturn(List.of());
    }

    /** @param startAt when the appointment was scheduled; @param updatedAt when it was last changed (the cancel). */
    private static Booking booking(String status, Instant startAt, Instant updatedAt) {
        return new Booking("bk-1", status, startAt.toString(), null,
                updatedAt == null ? null : updatedAt.toString(), "LOC", CUST,
                null, null, List.of(new AppointmentSegment(TM, VAR, 60)));
    }

    /** A ~$25 "Cancelation Policy" fee order for CUST, closed at {@code closedAt}. */
    private static Order feeOrder(Instant closedAt) {
        OrderLineItem line = new OrderLineItem("uid", "Cancelation Policy", "1", null,
                null, null, new Money(2500L, "USD"), null, null);
        return new Order("ord-fee", "LOC", CUST, "COMPLETED", closedAt.toString(), closedAt.toString(),
                List.of(line), null, null, null, null, null);
    }

    private MonthAggregation runFor(Booking b) {
        when(square.bookings(any(), any())).thenReturn(List.of(b));
        Instant t = Instant.parse(b.startAt());
        int year = t.atZone(java.time.ZoneOffset.UTC).getYear();
        int month = t.atZone(java.time.ZoneOffset.UTC).getMonthValue();
        return aggregator.aggregate(year, month, new BigDecimal("60.00"));
    }

    @Test
    @DisplayName("Seller-cancelled AFTER start → emitted as a cancellation")
    void cancelledAfterStartIsEmitted() {
        Instant start = Instant.now().minus(3, ChronoUnit.DAYS);
        MonthAggregation agg = runFor(booking("CANCELLED_BY_SELLER", start, start.plus(2, ChronoUnit.HOURS)));
        assertThat(agg.cancellations()).hasSize(1);
        var c = agg.cancellations().get(0);
        assertThat(c.bookingId()).isEqualTo("bk-1");
        assertThat(c.providerId()).isEqualTo(TM);
        assertThat(c.customerId()).isEqualTo(CUST);
        assertThat(c.gross()).isEqualByComparingTo("80.00");
        assertThat(agg.suspicious()).isEmpty();
    }

    @Test
    @DisplayName("Seller-cancelled BEFORE start (advance cancel) → not emitted")
    void cancelledBeforeStartNotEmitted() {
        Instant start = Instant.now().minus(3, ChronoUnit.DAYS);
        MonthAggregation agg = runFor(booking("CANCELLED_BY_SELLER", start, start.minus(1, ChronoUnit.DAYS)));
        assertThat(agg.cancellations()).isEmpty();
    }

    @Test
    @DisplayName("Cancelled after start but we charged a cancellation fee → not emitted")
    void feeChargedNotEmitted() {
        Instant start = Instant.now().minus(3, ChronoUnit.DAYS);
        when(square.completedOrders(any(), any())).thenReturn(List.of(feeOrder(start)));
        MonthAggregation agg = runFor(booking("CANCELLED_BY_SELLER", start, start.plus(2, ChronoUnit.HOURS)));
        assertThat(agg.cancellations()).isEmpty();
    }

    @Test
    @DisplayName("Customer-side cancellation → not emitted (not a provider action)")
    void customerCancelledNotEmitted() {
        Instant start = Instant.now().minus(3, ChronoUnit.DAYS);
        MonthAggregation agg = runFor(booking("CANCELLED_BY_CUSTOMER", start, start.plus(2, ChronoUnit.HOURS)));
        assertThat(agg.cancellations()).isEmpty();
    }

    @Test
    @DisplayName("Future seller cancellation (cancelled before its start) → not emitted")
    void futureSellerCancelNotEmitted() {
        Instant start = Instant.now().plus(3, ChronoUnit.DAYS);
        MonthAggregation agg = runFor(booking("CANCELLED_BY_SELLER", start, Instant.now()));
        assertThat(agg.cancellations()).isEmpty();
    }

    @Test
    @DisplayName("Accepted appointment → not a cancellation")
    void acceptedNotEmitted() {
        Instant start = Instant.now().minus(3, ChronoUnit.DAYS);
        MonthAggregation agg = runFor(booking("ACCEPTED", start, start.plus(2, ChronoUnit.HOURS)));
        assertThat(agg.cancellations()).isEmpty();
    }
}
