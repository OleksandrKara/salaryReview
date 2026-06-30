package com.salonreview.square;

import com.salonreview.square.SquareClient.Money;
import com.salonreview.square.SquareClient.Order;
import com.salonreview.square.SquareClient.Tender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SquareClient#isCashOrder} — the shared cash/card attribution used by both the
 * month aggregator and the revenue pulse, so they split tender identically.
 */
class SquareClientCashOrderTest {

    private static Money usd(long cents) {
        return new Money(cents, "USD");
    }

    private static Order orderWith(List<Tender> tenders) {
        return new Order("o1", "LOC", "CUST", "COMPLETED", "2026-06-10T16:00:00Z", "2026-06-10T16:00:00Z",
                List.of(), null, null, tenders);
    }

    @Test
    @DisplayName("a cash-only tender → cash order")
    void cashOnly() {
        assertThat(SquareClient.isCashOrder(orderWith(List.of(new Tender("t1", "CASH", usd(5000)))))).isTrue();
    }

    @Test
    @DisplayName("a card tender → not a cash order")
    void cardOnly() {
        assertThat(SquareClient.isCashOrder(orderWith(List.of(new Tender("t1", "CARD", usd(5000)))))).isFalse();
    }

    @Test
    @DisplayName("split payment: cash outweighs card → cash order; card outweighs cash → not")
    void splitPayment() {
        assertThat(SquareClient.isCashOrder(orderWith(List.of(
                new Tender("t1", "CASH", usd(6000)), new Tender("t2", "CARD", usd(4000)))))).isTrue();
        assertThat(SquareClient.isCashOrder(orderWith(List.of(
                new Tender("t1", "CASH", usd(4000)), new Tender("t2", "CARD", usd(6000)))))).isFalse();
    }

    @Test
    @DisplayName("null or empty tenders → not a cash order (defaults to card)")
    void noTenders() {
        assertThat(SquareClient.isCashOrder(orderWith(null))).isFalse();
        assertThat(SquareClient.isCashOrder(orderWith(List.of()))).isFalse();
    }
}
