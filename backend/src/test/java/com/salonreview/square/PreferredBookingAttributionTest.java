package com.salonreview.square;

import com.salonreview.repo.OwnerCustomerRepository;
import com.salonreview.square.SquareClient.*;
import com.salonreview.square.SquareMonthAggregator.AttributedService;
import com.salonreview.square.SquareMonthAggregator.MonthAggregation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Reproduces a real production case: a customer had a leftover, unpaid sibling booking (a stub from
 * a "4-hands" request that got split into two single-provider visits) sharing two SKUs with the real,
 * paid booking — and both bookings were touched within one second of each other by whatever edit split
 * the request, so the old per-line "closest updated_at" tie-break split a single $228 checkout across
 * two different providers. The one booking that actually explains the WHOLE order (all 4 line items)
 * must win outright, not just the two contested lines.
 */
class PreferredBookingAttributionTest {

    private static final String CUST = "SHANTEL";
    private static final String BAYAN = "TM-BAYAN";
    private static final String LESYA = "TM-LESYA";
    private static final String PEDICURE = "SV-PEDICURE";
    private static final String MANICURE = "SV-MANICURE";   // shared SKU
    private static final String REMOVAL = "SV-REMOVAL";     // shared SKU
    private static final String DESIGN = "SV-DESIGN";

    private SquareClient square;
    private SquareMonthAggregator aggregator;

    @BeforeEach
    void setUp() {
        square = mock(SquareClient.class);
        OwnerCustomerRepository ownerRepo = mock(OwnerCustomerRepository.class);
        aggregator = new SquareMonthAggregator(square, new CashNoteParser(), ownerRepo);
        when(square.locationTimeZone()).thenReturn("UTC");
        when(square.allTeamMembers()).thenReturn(List.of(
                new TeamMember(BAYAN, "Bayan", "Dandiyeva", "ACTIVE", false, null, null),
                new TeamMember(LESYA, "Lesya", "Petrova", "ACTIVE", false, null, null)));
        when(square.canonicalCustomerIds(any())).thenReturn(Map.of(CUST, CUST));
        when(square.catalogPrices(any())).thenReturn(Map.of(
                PEDICURE, new BigDecimal("104.00"), MANICURE, new BigDecimal("109.00"),
                REMOVAL, new BigDecimal("20.00"), DESIGN, new BigDecimal("20.00")));
    }

    private static Booking bayanUnpaidStub() {
        // 3:30pm — the leftover, never-paid single-provider booking sharing two SKUs with Lesya's.
        return new Booking("bk-bayan", "ACCEPTED", "2026-08-02T22:30:00Z", "2026-08-02T21:22:47Z",
                "2026-08-03T02:49:50Z", "LOC", CUST, null, null, List.of(
                        new AppointmentSegment(BAYAN, MANICURE, 120),
                        new AppointmentSegment(BAYAN, REMOVAL, 30)));
    }

    private static Booking lesyaPaidBooking() {
        // 6pm — the real, paid visit: covers all 4 of the order's line items.
        return new Booking("bk-lesya", "ACCEPTED", "2026-08-03T01:00:00Z", "2026-08-02T21:30:21Z",
                "2026-08-03T02:49:51Z", "LOC", CUST, null, null, List.of(
                        new AppointmentSegment(LESYA, PEDICURE, 90),
                        new AppointmentSegment(LESYA, MANICURE, 120),
                        new AppointmentSegment(LESYA, REMOVAL, 30),
                        new AppointmentSegment(LESYA, DESIGN, 30)));
    }

    private static OrderLineItem li(String svid, String name, String amount) {
        Money m = new Money(new BigDecimal(amount).movePointRight(2).longValueExact(), "USD");
        return new OrderLineItem("u-" + svid, name, "1", svid, m, m, m, null);
    }

    private static Order paidOrder() {
        return new Order("o1", "LOC", CUST, "COMPLETED", "2026-08-03T02:17:36Z", "2026-08-03T02:17:35Z",
                List.of(li(PEDICURE, "Pedicure", "104.00"), li(MANICURE, "Manicure", "109.00"),
                        li(REMOVAL, "Removal Gel", "20.00"), li(DESIGN, "Design", "20.00")),
                null, null, null, null);
    }

    @Test
    @DisplayName("A checkout the paying booking fully explains attributes entirely to that booking's provider")
    void wholeOrderAttributesToTheBookingThatExplainsIt() {
        // bk-bayan is listed FIRST and its updated_at (02:49:50Z) is 1 second closer to the order's
        // close (02:17:36Z) than bk-lesya's (02:49:51Z) — exactly the coincidence that made the old
        // per-line skew tie-break split this ticket. bk-lesya covers all 4 lines; bk-bayan covers 2.
        when(square.bookings(any(), any())).thenReturn(List.of(bayanUnpaidStub(), lesyaPaidBooking()));
        when(square.completedOrders(any(), any())).thenReturn(List.of(paidOrder()));

        MonthAggregation agg = aggregator.aggregate(2026, 8, new BigDecimal("50.00"));

        assertThat(agg.unmatched()).isEmpty();
        Set<String> providersOnTicket = agg.services().stream()
                .map(AttributedService::providerId).collect(java.util.stream.Collectors.toSet());
        assertThat(providersOnTicket).containsExactly(LESYA);
        BigDecimal total = agg.services().stream().map(AttributedService::gross)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(total).isEqualByComparingTo("253.00"); // 104 + 109 + 20 + 20
    }
}
