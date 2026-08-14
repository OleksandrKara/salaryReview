package com.salonreview.square;

import com.salonreview.square.SquareClient.Money;
import com.salonreview.square.SquareClient.Payment;
import com.salonreview.square.SquareMonthAggregator.BookingHint;
import com.salonreview.square.SquareMonthAggregator.OrphanPayment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SquareMonthAggregator#detectOrphanPayments} — the P0 finding that a payment
 * charged directly against a customer's card on file (bypassing the booking checkout) never produces
 * an Order, so the order-based reconciliation never sees it. Modeled on the two real cases that
 * surfaced this gap: a client paying part card / part cash where only the cash portion had an Order
 * (Diane), and a client with two separate card charges neither tied to any Order (Nicole).
 */
class SquareMonthAggregatorOrphanPaymentsTest {

    private static Money usd(long cents) { return new Money(cents, "USD"); }

    private static Payment payment(String id, String orderId, String customerId, String status,
                                   String createdAt, long cents) {
        return new Payment(id, orderId, customerId, status, createdAt, usd(cents), null);
    }

    @Test
    @DisplayName("a completed payment with no order_id at all is flagged as orphan")
    void noOrderIdIsOrphan() {
        // Nicole: two separate card charges, neither linked to any order.
        var payments = List.of(
                payment("PAY1", null, "NICOLE", "COMPLETED", "2026-08-12T23:30:00Z", 4500),
                payment("PAY2", null, "NICOLE", "COMPLETED", "2026-08-12T23:45:00Z", 6500));

        List<OrphanPayment> out = SquareMonthAggregator.detectOrphanPayments(
                payments, Set.of(), Map.of(), Map.of(), ZoneOffset.UTC, 2026, 8);

        assertThat(out).hasSize(2);
        assertThat(out).extracting(OrphanPayment::amount)
                .containsExactlyInAnyOrder(new java.math.BigDecimal("45.00"), new java.math.BigDecimal("65.00"));
        assertThat(out).allMatch(p -> p.note().contains("No linked order"));
    }

    @Test
    @DisplayName("a payment whose order_id IS among this month's known orders is not orphan — already accounted for")
    void knownOrderIdIsNotOrphan() {
        var payments = List.of(payment("PAY1", "ORD1", "DIANE", "COMPLETED", "2026-07-17T14:30:00Z", 1900));

        List<OrphanPayment> out = SquareMonthAggregator.detectOrphanPayments(
                payments, Set.of("ORD1"), Map.of(), Map.of(), ZoneOffset.UTC, 2026, 7);

        assertThat(out).isEmpty();
    }

    @Test
    @DisplayName("Diane: $19 card payment with no order at all is flagged, even though her $80 cash order already matched")
    void partialCardPaymentIsOrphan() {
        // The $80 cash order matched normally (not part of this detection at all — it's already in
        // `orders`/`services`). The $19 card charge has no order_id whatsoever.
        var payments = List.of(payment("PAY-CARD", null, "DIANE", "COMPLETED", "2026-07-17T14:35:00Z", 1900));
        var hints = Map.of("DIANE", List.of(new BookingHint("BK-DIANE", "PROV1", LocalDate.of(2026, 7, 17))));
        var names = Map.of("PROV1", "Anna");

        List<OrphanPayment> out = SquareMonthAggregator.detectOrphanPayments(
                payments, Set.of("ORD-CASH-80"), hints, names, ZoneOffset.UTC, 2026, 7);

        assertThat(out).hasSize(1);
        OrphanPayment op = out.get(0);
        assertThat(op.amount()).isEqualByComparingTo("19.00");
        assertThat(op.suggestedBookingId()).isEqualTo("BK-DIANE");
        assertThat(op.suggestedProviderId()).isEqualTo("PROV1");
        assertThat(op.suggestedProviderName()).isEqualTo("Anna");
    }

    @Test
    @DisplayName("suggests the nearest booking within 2 days for the same customer; ignores farther ones")
    void suggestsNearestBookingWithinTolerance() {
        var payments = List.of(payment("PAY1", null, "CUST1", "COMPLETED", "2026-08-12T20:30:00Z", 5000));
        var hints = Map.of("CUST1", List.of(
                new BookingHint("BK-FAR", "PROV-FAR", LocalDate.of(2026, 8, 1)),
                new BookingHint("BK-NEAR", "PROV-NEAR", LocalDate.of(2026, 8, 12))));

        List<OrphanPayment> out = SquareMonthAggregator.detectOrphanPayments(
                payments, Set.of(), hints, Map.of(), ZoneOffset.UTC, 2026, 8);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).suggestedBookingId()).isEqualTo("BK-NEAR");
    }

    @Test
    @DisplayName("no booking within 2 days → no suggestion, but still flagged as orphan")
    void noNearbyBookingStillFlagged() {
        var payments = List.of(payment("PAY1", null, "CUST1", "COMPLETED", "2026-08-12T20:30:00Z", 5000));
        var hints = Map.of("CUST1", List.of(new BookingHint("BK-FAR", "PROV-FAR", LocalDate.of(2026, 8, 1))));

        List<OrphanPayment> out = SquareMonthAggregator.detectOrphanPayments(
                payments, Set.of(), hints, Map.of(), ZoneOffset.UTC, 2026, 8);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).suggestedBookingId()).isNull();
        assertThat(out.get(0).suggestedProviderId()).isNull();
    }

    @Test
    @DisplayName("non-COMPLETED payments (e.g. FAILED, CANCELED) are ignored")
    void nonCompletedIgnored() {
        var payments = List.of(
                payment("PAY1", null, "CUST1", "FAILED", "2026-08-12T20:30:00Z", 5000),
                payment("PAY2", null, "CUST1", "CANCELED", "2026-08-12T20:30:00Z", 5000));

        List<OrphanPayment> out = SquareMonthAggregator.detectOrphanPayments(
                payments, Set.of(), Map.of(), Map.of(), ZoneOffset.UTC, 2026, 8);

        assertThat(out).isEmpty();
    }

    @Test
    @DisplayName("payments outside the requested month are ignored")
    void outsideMonthIgnored() {
        var payments = List.of(payment("PAY1", null, "CUST1", "COMPLETED", "2026-07-31T20:30:00Z", 5000));

        List<OrphanPayment> out = SquareMonthAggregator.detectOrphanPayments(
                payments, Set.of(), Map.of(), Map.of(), ZoneOffset.UTC, 2026, 8);

        assertThat(out).isEmpty();
    }

    @Test
    @DisplayName("zero/negative-amount payments are ignored")
    void zeroAmountIgnored() {
        var payments = List.of(payment("PAY1", null, "CUST1", "COMPLETED", "2026-08-12T20:30:00Z", 0));

        List<OrphanPayment> out = SquareMonthAggregator.detectOrphanPayments(
                payments, Set.of(), Map.of(), Map.of(), ZoneOffset.UTC, 2026, 8);

        assertThat(out).isEmpty();
    }

    @Test
    @DisplayName("never auto-attributed to revenue/commission — this is purely a detection list")
    void neverIncludesGrossOrChannel() {
        // Structural check: OrphanPayment has no `counted`/`channel`/commission-relevant fields at all,
        // so it cannot accidentally be folded into TierCommissionEngine inputs the way AttributedService is.
        var op = new OrphanPayment("2026-08-12", new java.math.BigDecimal("45.00"), "CUST1", "Nicole",
                null, null, null, "No linked order");
        assertThat(op.getClass().getRecordComponents()).extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("counted", "channel", "countedUnits");
    }
}
