package com.salonreview.square;

import com.salonreview.domain.OwnerCustomer;
import com.salonreview.repo.OwnerCustomerRepository;
import com.salonreview.square.SquareClient.*;
import com.salonreview.square.SquareMonthAggregator.AttributedService;
import com.salonreview.square.SquareMonthAggregator.MonthAggregation;
import com.salonreview.square.SquareMonthAggregator.ProviderMonth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Owner comps: a service rendered to an owner/family customer is never charged (no Square order), but
 * the provider is still credited their commission on the catalog menu price. Mirrors the real booking
 * eqkr2dd90axwlf — Anna Comegys did a $99 service for owner-customer Anna Kara, no payment taken.
 */
class OwnerCompAggregatorTest {

    private static final String OWNER_CUST = "CUSTOWNER";
    private static final String OTHER_CUST = "CUSTOTHER";
    private static final String VAR = "VAR1";
    private static final String TM = "TM1";

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
                new TeamMember(TM, "Anna", "Comegys", "ACTIVE", false, null, null)));
        when(square.catalogPrices(any())).thenReturn(Map.of(VAR, new BigDecimal("99.00")));
        when(square.catalogNames(any())).thenReturn(Map.of(VAR, "Nail Artist"));
        when(square.completedOrders(any(), any())).thenReturn(List.of()); // no payment by default
    }

    private static Booking booking(String customerId) {
        return new Booking("bk1", "ACCEPTED", "2026-05-18T15:00:00Z", null, "LOC", customerId,
                null, null, List.of(new AppointmentSegment(TM, VAR, 60)));
    }

    private void ownerCustomers(String... ids) {
        when(ownerRepo.findAll()).thenReturn(java.util.Arrays.stream(ids)
                .map(id -> OwnerCustomer.builder().squareCustomerId(id).build())
                .toList());
    }

    private static ProviderMonth anna(MonthAggregation agg) {
        return agg.providers().stream().filter(p -> p.name().contains("Anna")).findFirst().orElse(null);
    }

    @Test
    @DisplayName("Owner-customer booking with no order credits the provider at the menu price, counted")
    void ownerCompCredited() {
        ownerCustomers(OWNER_CUST);
        when(square.bookings(any(), any())).thenReturn(List.of(booking(OWNER_CUST)));

        MonthAggregation agg = aggregator.aggregate(2026, 5, new BigDecimal("50.00"));

        ProviderMonth p = anna(agg);
        assertThat(p).isNotNull();
        // May 18 → second half; paid like card so the provider keeps their commission rate.
        assertThat(p.secondHalf().cardRevenue()).isEqualByComparingTo("99.00");
        assertThat(p.secondHalf().countedServices()).isEqualTo(1); // counts toward the tier (>= $50)
        assertThat(agg.diagnostics().ownerComps).isEqualTo(1);

        AttributedService line = agg.services().stream()
                .filter(s -> "COMP".equals(s.channel())).findFirst().orElseThrow();
        assertThat(line.gross()).isEqualByComparingTo("99.00");
        assertThat(line.discount()).isEqualByComparingTo("0.00");
        assertThat(line.service()).isEqualTo("Nail Artist");
        assertThat(line.counted()).isTrue();
    }

    @Test
    @DisplayName("A non-owner customer's orderless booking is NOT credited (no fabricated pay)")
    void nonOwnerNotCredited() {
        ownerCustomers(OWNER_CUST);                       // owner list does NOT include this customer
        when(square.bookings(any(), any())).thenReturn(List.of(booking(OTHER_CUST)));

        MonthAggregation agg = aggregator.aggregate(2026, 5, new BigDecimal("50.00"));

        assertThat(anna(agg)).isNull();                   // nothing to pay — no order, not an owner
        assertThat(agg.diagnostics().ownerComps).isZero();
    }

    @Test
    @DisplayName("An owner booking that WAS paid (has an order) is not double-counted as a comp")
    void paidOwnerBookingNotDoubleCounted() {
        ownerCustomers(OWNER_CUST);
        when(square.bookings(any(), any())).thenReturn(List.of(booking(OWNER_CUST)));
        OrderLineItem li = new OrderLineItem("u1", "Nail Artist", "1", VAR,
                null, new Money(9900L, "USD"), new Money(9900L, "USD"), null);
        Order paid = new Order("o1", "LOC", OWNER_CUST, "COMPLETED", "2026-05-18T16:00:00Z",
                "2026-05-18T16:00:00Z", List.of(li), null, null, null);
        when(square.completedOrders(any(), any())).thenReturn(List.of(paid));

        MonthAggregation agg = aggregator.aggregate(2026, 5, new BigDecimal("50.00"));

        ProviderMonth p = anna(agg);
        assertThat(p.secondHalf().cardRevenue()).isEqualByComparingTo("99.00"); // paid once, via the order
        assertThat(agg.diagnostics().ownerComps).isZero();                      // not also as a comp
        assertThat(agg.services()).noneMatch(s -> "COMP".equals(s.channel()));
        assertThat(agg.services()).anyMatch(s -> "CARD".equals(s.channel()));
    }

    @Test
    @DisplayName("An owner booking with no resolvable catalog price is skipped (can't value it)")
    void ownerCompWithoutPriceSkipped() {
        ownerCustomers(OWNER_CUST);
        when(square.bookings(any(), any())).thenReturn(List.of(booking(OWNER_CUST)));
        when(square.catalogPrices(any())).thenReturn(Map.of()); // price unresolved

        MonthAggregation agg = aggregator.aggregate(2026, 5, new BigDecimal("50.00"));

        assertThat(anna(agg)).isNull();
        assertThat(agg.diagnostics().ownerComps).isZero();
        assertThat(agg.diagnostics().ownerCompsSkipped).isEqualTo(1);
    }
}
