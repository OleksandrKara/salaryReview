package com.salonreview.square;

import com.salonreview.repo.OwnerCustomerRepository;
import com.salonreview.square.SquareClient.*;
import com.salonreview.square.SquareMonthAggregator.AttributedService;
import com.salonreview.square.SquareMonthAggregator.MonthAggregation;
import com.salonreview.square.SquareMonthAggregator.UnmatchedLine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Two related cash-note fixes, both grounded in real production cases:
 *
 * <p>1. A cash-note amount can never exceed the service's own catalog price — if it does, it's
 * treated as a typo (an extra zero, a misplaced decimal) and capped, never inflating the commission
 * basis ({@link #amountExceedingCatalogIsCapped}).
 *
 * <p>2. A cash-note gap (collected &lt; catalog price) is checked against this month's already-found
 * unattributed sales for an exact same-customer, near-day, cent-exact match before being recorded as
 * a "salon discount" — the real pattern behind two production cases (Lupita Parra: $5 cash note +
 * $100 unattributed card charge; Diane Avila: $80 cash note + $19 unattributed card charge). In
 * every case, the provider's total commission (gross × rate) is proven unaffected by which path fires
 * — only which channel the money is booked under changes.
 */
class CashNoteGapAndCapTest {

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
        when(square.allTeamMembers()).thenReturn(List.of(new TeamMember(TM, "Susan", "A", "ACTIVE", false, null, null)));
        when(ownerRepo.findAll()).thenReturn(List.of());
        when(square.payments(any(), any())).thenReturn(List.of());
        when(square.customerNames(any())).thenReturn(Map.of());
    }

    // Day 5 (FIRST half, days 1-15) keeps every fixture's numbers on providers().get(0).firstHalf().
    private static Booking cashNoteBooking(String note, String... variationIds) {
        return cashNoteBookingOn("2026-07-05T15:00:00Z", note, variationIds);
    }

    private static Booking cashNoteBookingOn(String startAt, String note, String... variationIds) {
        var segs = java.util.Arrays.stream(variationIds).map(v -> new AppointmentSegment(TM, v, 60)).toList();
        return new Booking("bk1", "ACCEPTED", startAt, null, null, "LOC", CUST, note, null, segs);
    }

    private static OrderLineItem customAmountLine(String amount) {
        Money m = new Money(new BigDecimal(amount).movePointRight(2).longValueExact(), "USD");
        return new OrderLineItem("u1", null, "1", null, m, m, m, null);
    }

    private static Order orderOn(String isoInstant, OrderLineItem... lines) {
        return new Order("o1", "LOC", CUST, "COMPLETED", isoInstant, isoInstant, List.of(lines), null, null,
                List.of(new Tender("t1", "CARD", lines[0].totalMoney())), null);
    }

    private static AttributedService cashNoteLine(MonthAggregation agg) {
        return agg.services().stream().filter(s -> "CASH-NOTE".equals(s.channel())).findFirst().orElseThrow();
    }

    // --- Cap ---

    @Test
    @DisplayName("note amount exceeding the catalog price is capped, not trusted — protects against a typo overpaying the provider")
    void amountExceedingCatalogIsCapped() {
        // Real scenario this guards: "cashew $500" typed instead of "$50".
        when(square.bookings(any(), any())).thenReturn(List.of(cashNoteBooking("cashew $500.00", "VAR1")));
        when(square.completedOrders(any(), any())).thenReturn(List.of());
        when(square.catalogPrices(any())).thenReturn(Map.of("VAR1", new BigDecimal("50.00")));

        MonthAggregation agg = aggregator.aggregate(2026, 7, new BigDecimal("30.00"));

        AttributedService line = cashNoteLine(agg);
        assertThat(line.gross()).isEqualByComparingTo("50.00"); // capped at catalog, never $500
        assertThat(line.net()).isEqualByComparingTo("50.00");
        assertThat(line.discount()).isEqualByComparingTo("0.00");
        assertThat(line.service()).contains("capped");
        assertThat(agg.diagnostics().getCashNoteAmountCapped()).isEqualTo(1);
        // Provider's commission basis is the catalog price, not the typo'd $500.
        assertThat(agg.providers().get(0).firstHalf().cashGross()).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("when the catalog price doesn't resolve at all, the note's amount is trusted as-is (no anchor to cap against)")
    void unresolvedCatalogIsNotCapped() {
        when(square.bookings(any(), any())).thenReturn(List.of(cashNoteBooking("cashew $75.00", "VAR-UNKNOWN")));
        when(square.completedOrders(any(), any())).thenReturn(List.of());
        when(square.catalogPrices(any())).thenReturn(Map.of()); // VAR-UNKNOWN never resolves

        MonthAggregation agg = aggregator.aggregate(2026, 7, new BigDecimal("30.00"));

        AttributedService line = cashNoteLine(agg);
        assertThat(line.gross()).isEqualByComparingTo("75.00"); // trusted, unchanged behavior
        assertThat(agg.diagnostics().getCashNoteAmountCapped()).isEqualTo(0);
    }

    // --- Gap matching ---

    @Test
    @DisplayName("Lupita Parra: $5 cash note + $100 unattributed custom-amount card charge → gap closed, no phantom discount, no extra commission")
    void cardGapMatchClosesPhantomDiscount() {
        when(square.bookings(any(), any())).thenReturn(List.of(cashNoteBooking("cashew $5.00", "VAR1")));
        when(square.completedOrders(any(), any())).thenReturn(List.of(
                orderOn("2026-07-05T20:00:00Z", customAmountLine("100.00"))));
        when(square.catalogPrices(any())).thenReturn(Map.of("VAR1", new BigDecimal("105.00")));

        MonthAggregation agg = aggregator.aggregate(2026, 7, new BigDecimal("30.00"));

        assertThat(agg.unmatched()).isEmpty(); // the $100 line was reclaimed, not left dangling
        assertThat(agg.diagnostics().getCashNoteGapMatches()).isEqualTo(1);
        assertThat(agg.diagnostics().getUnmatchedLineItems()).isEqualTo(0);

        AttributedService noteLine = cashNoteLine(agg);
        assertThat(noteLine.gross()).isEqualByComparingTo("5.00");   // only the cash portion now
        assertThat(noteLine.discount()).isEqualByComparingTo("0.00"); // no more phantom discount

        AttributedService cardLine = agg.services().stream()
                .filter(s -> "CARD".equals(s.channel())).findFirst().orElseThrow();
        assertThat(cardLine.gross()).isEqualByComparingTo("100.00");
        assertThat(cardLine.counted()).isFalse(); // doesn't double-count the tier unit

        // The invariant: total commission basis for this one visit is still exactly $105,
        // split $5 cash + $100 card — never $105 + $100 double-counted.
        var half = agg.providers().get(0).firstHalf();
        assertThat(half.cashGross().add(half.cardRevenue())).isEqualByComparingTo("105.00");
        assertThat(half.cashCollected()).isEqualByComparingTo("5.00");
    }

    @Test
    @DisplayName("Diane Avila: $80 cash note + $19 unattributed card charge → same fix, real numbers")
    void dianeRealNumbers() {
        when(square.bookings(any(), any()))
                .thenReturn(List.of(cashNoteBookingOn("2026-07-17T15:00:00Z", "cashew $80.00", "VAR1")));
        when(square.completedOrders(any(), any())).thenReturn(List.of(
                orderOn("2026-07-17T17:05:37Z", customAmountLine("19.00"))));
        when(square.catalogPrices(any())).thenReturn(Map.of("VAR1", new BigDecimal("99.00")));

        MonthAggregation agg = aggregator.aggregate(2026, 7, new BigDecimal("30.00"));

        assertThat(agg.unmatched()).isEmpty();
        var half = agg.providers().get(0).secondHalf(); // July 17 is day > 15
        assertThat(half.cashGross().add(half.cardRevenue())).isEqualByComparingTo("99.00");
    }

    @Test
    @DisplayName("no matching unattributed line exists → falls back to today's behavior exactly (phantom discount recorded, nothing removed)")
    void noMatchFallsBackToExistingBehavior() {
        when(square.bookings(any(), any())).thenReturn(List.of(cashNoteBooking("cashew $5.00", "VAR1")));
        when(square.completedOrders(any(), any())).thenReturn(List.of()); // nothing else to find
        when(square.catalogPrices(any())).thenReturn(Map.of("VAR1", new BigDecimal("105.00")));

        MonthAggregation agg = aggregator.aggregate(2026, 7, new BigDecimal("30.00"));

        AttributedService line = cashNoteLine(agg);
        assertThat(line.gross()).isEqualByComparingTo("105.00");
        assertThat(line.discount()).isEqualByComparingTo("100.00"); // unchanged fallback
        assertThat(agg.diagnostics().getCashNoteGapMatches()).isEqualTo(0);
    }

    @Test
    @DisplayName("a candidate with the right customer but the wrong amount is never matched — exact cents only, no fuzzy tolerance")
    void wrongAmountNeverMatches() {
        when(square.bookings(any(), any())).thenReturn(List.of(cashNoteBooking("cashew $5.00", "VAR1")));
        when(square.completedOrders(any(), any())).thenReturn(List.of(
                orderOn("2026-07-05T20:00:00Z", customAmountLine("99.99")))); // off by a cent from the true $100 gap
        when(square.catalogPrices(any())).thenReturn(Map.of("VAR1", new BigDecimal("105.00")));

        MonthAggregation agg = aggregator.aggregate(2026, 7, new BigDecimal("30.00"));

        assertThat(agg.unmatched()).hasSize(1); // untouched — no match
        assertThat(agg.diagnostics().getCashNoteGapMatches()).isEqualTo(0);
        assertThat(cashNoteLine(agg).discount()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("a same-amount candidate for a DIFFERENT customer is never matched")
    void differentCustomerNeverMatches() {
        when(square.bookings(any(), any())).thenReturn(List.of(cashNoteBooking("cashew $5.00", "VAR1")));
        Order otherCustomerOrder = new Order("o2", "LOC", "SOMEONE-ELSE", "COMPLETED",
                "2026-07-05T20:00:00Z", "2026-07-05T20:00:00Z", List.of(customAmountLine("100.00")),
                null, null, List.of(new Tender("t1", "CARD", new Money(10000L, "USD"))), null);
        when(square.completedOrders(any(), any())).thenReturn(List.of(otherCustomerOrder));
        when(square.catalogPrices(any())).thenReturn(Map.of("VAR1", new BigDecimal("105.00")));

        MonthAggregation agg = aggregator.aggregate(2026, 7, new BigDecimal("30.00"));

        assertThat(agg.unmatched()).hasSize(1);
        assertThat(agg.diagnostics().getCashNoteGapMatches()).isEqualTo(0);
    }

    @Test
    @DisplayName("findGapMatch picks the nearer day when two candidates share the same amount")
    void findGapMatchPrefersNearerDay() {
        var far = new UnmatchedLine("2026-07-01", "svc", new BigDecimal("100.00"), "CARD", CUST, null);
        var near = new UnmatchedLine("2026-07-29", "svc", new BigDecimal("100.00"), "CARD", CUST, null);

        UnmatchedLine picked = SquareMonthAggregator.findGapMatch(
                List.of(far, near), CUST, LocalDate.of(2026, 7, 29), new BigDecimal("100.00"));

        assertThat(picked).isSameAs(near);
    }

    @Test
    @DisplayName("findGapMatch respects the 2-day tolerance — nothing further out ever matches")
    void findGapMatchRespectsTolerance() {
        var tooFar = new UnmatchedLine("2026-07-01", "svc", new BigDecimal("100.00"), "CARD", CUST, null);

        UnmatchedLine picked = SquareMonthAggregator.findGapMatch(
                List.of(tooFar), CUST, LocalDate.of(2026, 7, 29), new BigDecimal("100.00"));

        assertThat(picked).isNull();
    }
}
