package com.salonreview.marketing;

import com.salonreview.domain.SquareBookingMirror;
import com.salonreview.domain.SquareOrderMirror;
import com.salonreview.repo.SquareOrderMirrorRepository;
import com.salonreview.square.CashNoteParser;
import com.salonreview.square.SquareMonthAggregator.BookingPayment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MarketingBookingPaymentMatcherTest {

    private SquareOrderMirrorRepository orderRepository;
    private MarketingBookingPaymentMatcher matcher;

    @BeforeEach
    void setUp() {
        orderRepository = mock(SquareOrderMirrorRepository.class);
        matcher = new MarketingBookingPaymentMatcher(orderRepository, new CashNoteParser());
    }

    private static SquareBookingMirror booking(Instant startAt, String... variationIds) {
        List<SquareBookingMirror.Segment> segments = java.util.Arrays.stream(variationIds)
                .map(v -> new SquareBookingMirror.Segment("TM1", v, 60)).toList();
        return SquareBookingMirror.builder().startAt(startAt).appointmentSegments(segments).build();
    }

    @Test
    @DisplayName("matches a real checked-out order by customer + matching service variation, near the booking time")
    void matchesCheckedOutOrder() {
        Instant start = Instant.parse("2026-06-01T15:00:00Z");
        SquareBookingMirror b = booking(start, "VAR1");

        SquareOrderMirror.LineItem li = new SquareOrderMirror.LineItem("VAR1", null,
                new BigDecimal("100.00"), new BigDecimal("90.00"), new BigDecimal("10.00"), null);
        SquareOrderMirror order = SquareOrderMirror.builder()
                .closedAt(start.plusSeconds(600))
                .lineItems(List.of(li))
                .tenders(List.of(new SquareOrderMirror.Tender("CARD", new BigDecimal("90.00"))))
                .build();
        when(orderRepository.findByBusinessIdAndSquareCustomerIdAndClosedAtBetween(any(), any(), any(), any()))
                .thenReturn(List.of(order));

        Optional<BookingPayment> result = matcher.match(1L, "CUST1", b, Map.of());

        assertThat(result).isPresent();
        assertThat(result.get().channel()).isEqualTo("CARD");
        assertThat(result.get().collected()).isEqualByComparingTo("90.00");
        assertThat(result.get().gross()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("a CASH tender is reported as channel CASH, not CARD")
    void detectsCashTender() {
        Instant start = Instant.parse("2026-06-01T15:00:00Z");
        SquareBookingMirror b = booking(start, "VAR1");
        SquareOrderMirror.LineItem li = new SquareOrderMirror.LineItem("VAR1", null,
                new BigDecimal("50.00"), new BigDecimal("50.00"), BigDecimal.ZERO, null);
        SquareOrderMirror order = SquareOrderMirror.builder()
                .closedAt(start).lineItems(List.of(li))
                .tenders(List.of(new SquareOrderMirror.Tender("CASH", new BigDecimal("50.00"))))
                .build();
        when(orderRepository.findByBusinessIdAndSquareCustomerIdAndClosedAtBetween(any(), any(), any(), any()))
                .thenReturn(List.of(order));

        Optional<BookingPayment> result = matcher.match(1L, "CUST1", b, Map.of());

        assertThat(result.get().channel()).isEqualTo("CASH");
    }

    @Test
    @DisplayName("no matching order — falls back to the booking's own cash-note")
    void fallsBackToCashNote() {
        Instant start = Instant.parse("2026-06-01T15:00:00Z");
        SquareBookingMirror b = SquareBookingMirror.builder()
                .startAt(start)
                .appointmentSegments(List.of(new SquareBookingMirror.Segment("TM1", "VAR1", 60)))
                .sellerNote("cashew $80")
                .build();
        when(orderRepository.findByBusinessIdAndSquareCustomerIdAndClosedAtBetween(any(), any(), any(), any()))
                .thenReturn(List.of());

        Optional<BookingPayment> result = matcher.match(1L, "CUST1", b, Map.of("VAR1", new BigDecimal("100.00")));

        assertThat(result).isPresent();
        assertThat(result.get().channel()).isEqualTo("CASH-NOTE");
        assertThat(result.get().collected()).isEqualByComparingTo("80.00");
        assertThat(result.get().gross()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("cash-note amount exceeding the catalog price is capped, not trusted")
    void cashNoteAmountCapped() {
        Instant start = Instant.parse("2026-06-01T15:00:00Z");
        SquareBookingMirror b = SquareBookingMirror.builder()
                .startAt(start)
                .appointmentSegments(List.of(new SquareBookingMirror.Segment("TM1", "VAR1", 60)))
                .sellerNote("cashew $500")
                .build();
        when(orderRepository.findByBusinessIdAndSquareCustomerIdAndClosedAtBetween(any(), any(), any(), any()))
                .thenReturn(List.of());

        Optional<BookingPayment> result = matcher.match(1L, "CUST1", b, Map.of("VAR1", new BigDecimal("50.00")));

        assertThat(result.get().collected()).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("no matching order and no cash note at all — unresolved")
    void unresolvedWhenNothingMatches() {
        Instant start = Instant.parse("2026-06-01T15:00:00Z");
        SquareBookingMirror b = booking(start, "VAR1");
        when(orderRepository.findByBusinessIdAndSquareCustomerIdAndClosedAtBetween(any(), any(), any(), any()))
                .thenReturn(List.of());

        Optional<BookingPayment> result = matcher.match(1L, "CUST1", b, Map.of());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("an order for the same customer around the same time but for a DIFFERENT service is not matched")
    void ignoresOrderForDifferentService() {
        Instant start = Instant.parse("2026-06-01T15:00:00Z");
        SquareBookingMirror b = booking(start, "VAR1");
        SquareOrderMirror.LineItem unrelated = new SquareOrderMirror.LineItem("VAR-OTHER", null,
                new BigDecimal("30.00"), new BigDecimal("30.00"), BigDecimal.ZERO, null);
        SquareOrderMirror order = SquareOrderMirror.builder()
                .closedAt(start).lineItems(List.of(unrelated))
                .tenders(List.of(new SquareOrderMirror.Tender("CARD", new BigDecimal("30.00"))))
                .build();
        when(orderRepository.findByBusinessIdAndSquareCustomerIdAndClosedAtBetween(any(), any(), any(), any()))
                .thenReturn(List.of(order));

        Optional<BookingPayment> result = matcher.match(1L, "CUST1", b, Map.of());

        assertThat(result).isEmpty();
    }

    // --- matchAll: the batched sibling backing the contact info panel's fix (2026-09-08) ---

    @Test
    @DisplayName("matchAll: one order query for all bookings, each still matched against its own MATCH_WINDOW")
    void matchAllQueriesOnceAndMatchesEachBookingIndependently() {
        Instant start1 = Instant.parse("2026-06-01T15:00:00Z");
        Instant start2 = Instant.parse("2026-07-01T15:00:00Z");
        SquareBookingMirror b1 = SquareBookingMirror.builder().squareBookingId("bk1").startAt(start1)
                .appointmentSegments(List.of(new SquareBookingMirror.Segment("TM1", "VAR1", 60))).build();
        SquareBookingMirror b2 = SquareBookingMirror.builder().squareBookingId("bk2").startAt(start2)
                .appointmentSegments(List.of(new SquareBookingMirror.Segment("TM1", "VAR2", 60))).build();

        SquareOrderMirror.LineItem li1 = new SquareOrderMirror.LineItem("VAR1", null,
                new BigDecimal("40.00"), new BigDecimal("40.00"), BigDecimal.ZERO, null);
        SquareOrderMirror order1 = SquareOrderMirror.builder().closedAt(start1)
                .lineItems(List.of(li1)).tenders(List.of(new SquareOrderMirror.Tender("CARD", new BigDecimal("40.00")))).build();
        SquareOrderMirror.LineItem li2 = new SquareOrderMirror.LineItem("VAR2", null,
                new BigDecimal("60.00"), new BigDecimal("60.00"), BigDecimal.ZERO, null);
        SquareOrderMirror order2 = SquareOrderMirror.builder().closedAt(start2)
                .lineItems(List.of(li2)).tenders(List.of(new SquareOrderMirror.Tender("CASH", new BigDecimal("60.00")))).build();

        when(orderRepository.findByBusinessIdAndSquareCustomerId(1L, "CUST1")).thenReturn(List.of(order1, order2));

        Map<String, BookingPayment> results = matcher.matchAll(1L, "CUST1", List.of(b1, b2), Map.of());

        assertThat(results).hasSize(2);
        assertThat(results.get("bk1").channel()).isEqualTo("CARD");
        assertThat(results.get("bk1").collected()).isEqualByComparingTo("40.00");
        assertThat(results.get("bk2").channel()).isEqualTo("CASH");
        assertThat(results.get("bk2").collected()).isEqualByComparingTo("60.00");
        org.mockito.Mockito.verify(orderRepository, org.mockito.Mockito.times(1))
                .findByBusinessIdAndSquareCustomerId(1L, "CUST1");
    }

    @Test
    @DisplayName("matchAll: an order outside a booking's own MATCH_WINDOW is not matched to it, even though it's in the shared fetched list")
    void matchAllRespectsPerBookingWindowAgainstSharedList() {
        Instant start = Instant.parse("2026-06-01T15:00:00Z");
        SquareBookingMirror b = SquareBookingMirror.builder().squareBookingId("bk1").startAt(start)
                .appointmentSegments(List.of(new SquareBookingMirror.Segment("TM1", "VAR1", 60))).build();
        SquareOrderMirror.LineItem li = new SquareOrderMirror.LineItem("VAR1", null,
                new BigDecimal("40.00"), new BigDecimal("40.00"), BigDecimal.ZERO, null);
        SquareOrderMirror farOrder = SquareOrderMirror.builder().closedAt(start.plus(Duration.ofDays(10)))
                .lineItems(List.of(li)).tenders(List.of(new SquareOrderMirror.Tender("CARD", new BigDecimal("40.00")))).build();
        when(orderRepository.findByBusinessIdAndSquareCustomerId(1L, "CUST1")).thenReturn(List.of(farOrder));

        Map<String, BookingPayment> results = matcher.matchAll(1L, "CUST1", List.of(b), Map.of());

        assertThat(results).doesNotContainKey("bk1");
    }

    @Test
    @DisplayName("matchAll: null canonicalCustomerId falls back to cash-note per booking, no order query at all")
    void matchAllWithNullCustomerIdUsesCashNoteOnly() {
        Instant start = Instant.parse("2026-06-01T15:00:00Z");
        SquareBookingMirror b = SquareBookingMirror.builder().squareBookingId("bk1").startAt(start)
                .appointmentSegments(List.of(new SquareBookingMirror.Segment("TM1", "VAR1", 60)))
                .sellerNote("cashew $80").build();

        Map<String, BookingPayment> results = matcher.matchAll(1L, null, List.of(b), Map.of("VAR1", new BigDecimal("100.00")));

        assertThat(results.get("bk1").channel()).isEqualTo("CASH-NOTE");
        assertThat(results.get("bk1").collected()).isEqualByComparingTo("80.00");
        org.mockito.Mockito.verifyNoInteractions(orderRepository);
    }
}
