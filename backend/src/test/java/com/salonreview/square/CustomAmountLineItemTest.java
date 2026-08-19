package com.salonreview.square;

import com.salonreview.repo.OwnerCustomerRepository;
import com.salonreview.repo.SalonConfigRepository;
import com.salonreview.square.SquareClient.*;
import com.salonreview.square.SquareMonthAggregator.MonthAggregation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A Square order line item with no {@code catalog_object_id} at all — Square's "Custom Amount" /
 * type-an-amount charge, rung up by hand instead of picking the real service — used to be silently
 * `continue`d before ever reaching the unmatched-tracking logic, so the money vanished entirely: not
 * paid to any provider, not shown as "unattributed" for the owner to review. Confirmed against two
 * real payment-accounting gaps (a $19 card charge on Diane Avila's July 17 visit, and a $45+$65 pair
 * on Nicole Client's August 12 visit) — both had a genuine COMPLETED Square order and payment, just no
 * catalog line, and both were completely invisible in the app until this fix.
 */
class CustomAmountLineItemTest {

    private static final String CUST = "C1";
    private static final String TM = "TM1";

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
        aggregator = new SquareMonthAggregator(squareClientProvider, new CashNoteParser(), ownerRepo, currentBusinessContext, mock(SalonConfigRepository.class));
        when(square.locationTimeZone()).thenReturn("UTC");
        when(square.allTeamMembers()).thenReturn(List.of(new TeamMember(TM, "Anna", "C", "ACTIVE", false, null, null)));
        when(ownerRepo.findAllByBusinessId(1L)).thenReturn(List.of());
        when(square.bookings(any(), any())).thenReturn(List.of());
        when(square.payments(any(), any())).thenReturn(List.of());
        when(square.customerNames(any())).thenReturn(Map.of());
    }

    private static OrderLineItem customAmountLine(String name, String amount) {
        Money m = new Money(new BigDecimal(amount).movePointRight(2).longValueExact(), "USD");
        // catalogObjectId is null — this is exactly what Square sends for a "Custom Amount" charge.
        return new OrderLineItem("u1", name, "1", null, m, m, m, null, null);
    }

    private static Order orderWith(OrderLineItem... lines) {
        return orderOn("2026-07-17T17:05:41Z", "2026-07-17T17:05:37Z", lines);
    }

    private static Order orderOn(String closedAt, String createdAt, OrderLineItem... lines) {
        return new Order("o1", "LOC", CUST, "COMPLETED", closedAt, createdAt,
                List.of(lines), null, null,
                List.of(new Tender("t1", "CARD", lines[0].totalMoney())), null, null);
    }

    @Test
    @DisplayName("a custom-amount line item (Diane's real $19 charge) is NOT silently dropped — it's surfaced as unattributed")
    void customAmountLineSurfacedAsUnmatched() {
        when(square.completedOrders(any(), any())).thenReturn(List.of(orderWith(customAmountLine(null, "19.00"))));
        when(square.catalogPrices(any())).thenReturn(Map.of());

        MonthAggregation agg = aggregator.aggregate(2026, 7, new BigDecimal("50.00"));

        assertThat(agg.services()).isEmpty(); // never attributed to any provider
        assertThat(agg.diagnostics().getUnmatchedLineItems()).isEqualTo(1);
        assertThat(agg.diagnostics().getUnmatchedRevenue()).isEqualByComparingTo("19.00");
        assertThat(agg.unmatched()).hasSize(1);
        assertThat(agg.unmatched().get(0).gross()).isEqualByComparingTo("19.00");
        assertThat(agg.unmatched().get(0).channel()).isEqualTo("CARD");
        // Square sends no `name` for a custom-amount line — falls back to a readable label, not null/blank.
        assertThat(agg.unmatched().get(0).service()).isEqualTo("Custom amount (no catalog item)");
    }

    @Test
    @DisplayName("two custom-amount lines on separate orders (Nicole's real $45 + $65) both surface, independently")
    void twoCustomAmountLinesBothSurfaced() {
        when(square.completedOrders(any(), any())).thenReturn(List.of(
                orderOn("2026-08-13T02:10:19Z", "2026-08-13T02:10:07Z", customAmountLine(null, "45.00")),
                orderOn("2026-08-13T02:09:07Z", "2026-08-13T02:08:57Z", customAmountLine(null, "65.00"))));
        when(square.catalogPrices(any())).thenReturn(Map.of());

        MonthAggregation agg = aggregator.aggregate(2026, 8, new BigDecimal("50.00"));

        assertThat(agg.diagnostics().getUnmatchedLineItems()).isEqualTo(2);
        assertThat(agg.diagnostics().getUnmatchedRevenue()).isEqualByComparingTo("110.00");
        assertThat(agg.unmatched()).extracting(u -> u.gross())
                .containsExactlyInAnyOrder(new BigDecimal("45.00"), new BigDecimal("65.00"));
    }

    @Test
    @DisplayName("a real catalog-linked line on the same order still matches its booking normally — the fix doesn't touch normal lines")
    void normalCatalogLineUnaffected() {
        var booking = new Booking("bk1", "ACCEPTED", "2026-07-17T15:00:00Z", null, null, "LOC", CUST, null, null,
                List.of(new AppointmentSegment(TM, "VAR1", 60)));
        Money g = new Money(10000L, "USD");
        var normalLine = new OrderLineItem("u2", "Manicure", "1", "VAR1", g, g, g, null, null);
        when(square.bookings(any(), any())).thenReturn(List.of(booking));
        when(square.completedOrders(any(), any())).thenReturn(List.of(orderWith(normalLine)));
        when(square.catalogPrices(any())).thenReturn(Map.of("VAR1", new BigDecimal("100.00")));

        MonthAggregation agg = aggregator.aggregate(2026, 7, new BigDecimal("50.00"));

        assertThat(agg.services()).hasSize(1);
        assertThat(agg.services().get(0).providerId()).isEqualTo(TM);
        assertThat(agg.unmatched()).isEmpty();
    }
}
