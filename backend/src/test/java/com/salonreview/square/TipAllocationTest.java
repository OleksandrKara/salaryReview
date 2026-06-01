package com.salonreview.square;

import com.salonreview.repo.OwnerCustomerRepository;
import com.salonreview.square.SquareClient.*;
import com.salonreview.square.SquareMonthAggregator.AttributedService;
import com.salonreview.square.SquareMonthAggregator.MonthAggregation;
import com.salonreview.square.SquareMonthAggregator.ProviderMonth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Per-transaction tips: the order tip is split across the distinct providers on the ticket (the payout
 * basis), and each provider's share is spread across their line(s) on that order for the trace, so the
 * rows reconcile with the provider's tip total.
 */
class TipAllocationTest {

    private static final String CUST = "C1";
    private static final String TM = "TM1";

    private SquareClient square;
    private SquareMonthAggregator aggregator;

    @BeforeEach
    void setUp() {
        square = mock(SquareClient.class);
        OwnerCustomerRepository ownerRepo = mock(OwnerCustomerRepository.class);
        aggregator = new SquareMonthAggregator(square, new CashNoteParser(), ownerRepo);
        when(square.locationTimeZone()).thenReturn("UTC");
        when(square.allTeamMembers()).thenReturn(List.of(new TeamMember(TM, "Anna", "C", "ACTIVE", false, null, null)));
        when(ownerRepo.findAll()).thenReturn(List.of());
        when(square.completedOrders(any(), any())).thenReturn(List.of());
        when(square.bookings(any(), any())).thenReturn(List.of());
    }

    private static Booking booking(String... variationIds) {
        var segs = java.util.Arrays.stream(variationIds)
                .map(v -> new AppointmentSegment(TM, v, 60)).toList();
        return new Booking("bk1", "ACCEPTED", "2026-05-10T15:00:00Z", null, "LOC", CUST, null, null, segs);
    }

    private static OrderLineItem li(String var, String gross) {
        Money g = new Money(new BigDecimal(gross).movePointRight(2).longValueExact(), "USD");
        return new OrderLineItem("u-" + var, var, "1", var, null, g, g, null);
    }

    private static Order order(BigDecimal tipDollars, OrderLineItem... lines) {
        Money tip = new Money(tipDollars.movePointRight(2).longValueExact(), "USD");
        return new Order("o1", "LOC", CUST, "COMPLETED", "2026-05-10T16:00:00Z", "2026-05-10T16:00:00Z",
                List.of(lines), tip, null, null);
    }

    private static AttributedService lineFor(MonthAggregation agg, String service) {
        return agg.services().stream().filter(s -> service.equals(s.service())).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("Single-line ticket: the whole order tip lands on that line and on the payout")
    void singleLineTip() {
        when(square.bookings(any(), any())).thenReturn(List.of(booking("VAR1")));
        when(square.completedOrders(any(), any())).thenReturn(List.of(order(new BigDecimal("20.00"), li("VAR1", "100"))));
        when(square.catalogPrices(any())).thenReturn(Map.of("VAR1", new BigDecimal("100.00")));

        MonthAggregation agg = aggregator.aggregate(2026, 5, new BigDecimal("50.00"));

        assertThat(lineFor(agg, "VAR1").tip()).isEqualByComparingTo("20.00");
        ProviderMonth p = agg.providers().get(0);
        assertThat(p.firstHalf().cardTips()).isEqualByComparingTo("20.00"); // payout tip unchanged
    }

    @Test
    @DisplayName("Multi-line same provider: tip spreads by gross, rows sum exactly to the order tip")
    void multiLineTipByGross() {
        when(square.bookings(any(), any())).thenReturn(List.of(booking("VAR1", "VAR2")));
        when(square.completedOrders(any(), any()))
                .thenReturn(List.of(order(new BigDecimal("40.00"), li("VAR1", "100"), li("VAR2", "300"))));
        when(square.catalogPrices(any()))
                .thenReturn(Map.of("VAR1", new BigDecimal("100.00"), "VAR2", new BigDecimal("300.00")));

        MonthAggregation agg = aggregator.aggregate(2026, 5, new BigDecimal("50.00"));

        // 40 * 100/400 = 10 ; remainder 30 to the larger line.
        assertThat(lineFor(agg, "VAR1").tip()).isEqualByComparingTo("10.00");
        assertThat(lineFor(agg, "VAR2").tip()).isEqualByComparingTo("30.00");
        BigDecimal rowsSum = agg.services().stream().map(AttributedService::tip).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(rowsSum).isEqualByComparingTo("40.00");
        assertThat(agg.providers().get(0).firstHalf().cardTips()).isEqualByComparingTo("40.00");
    }

    @Test
    @DisplayName("No tip on the order → lines carry zero")
    void noTip() {
        when(square.bookings(any(), any())).thenReturn(List.of(booking("VAR1")));
        when(square.completedOrders(any(), any())).thenReturn(List.of(order(BigDecimal.ZERO, li("VAR1", "100"))));
        when(square.catalogPrices(any())).thenReturn(Map.of("VAR1", new BigDecimal("100.00")));

        MonthAggregation agg = aggregator.aggregate(2026, 5, new BigDecimal("50.00"));

        assertThat(lineFor(agg, "VAR1").tip()).isEqualByComparingTo("0.00");
    }
}
