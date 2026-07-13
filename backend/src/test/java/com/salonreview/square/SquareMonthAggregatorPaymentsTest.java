package com.salonreview.square;

import com.salonreview.square.SquareMonthAggregator.AttributedService;
import com.salonreview.square.SquareMonthAggregator.BookingPayment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SquareMonthAggregator#paymentsByBookingId} — the shared helper the
 * marketing Contacts and Analytics tabs both use to show what was actually collected for a
 * booking, reusing the same matched payroll lines {@code aggregate()} already computes.
 */
class SquareMonthAggregatorPaymentsTest {

    private static AttributedService line(String bookingId, String customerId, String channel,
                                           String gross, String net) {
        return new AttributedService("p1", "P", "2026-07-05", "FIRST", "Manicure",
                new BigDecimal(gross), BigDecimal.ZERO, new BigDecimal(net), BigDecimal.ZERO,
                true, 1, 1, false, channel, null, bookingId, customerId, null);
    }

    @Test
    @DisplayName("sums multiple line items for the same booking into one payment entry")
    void sumsMultiServiceBooking() {
        var lines = List.of(
                line("BK1", "CUST1", "CARD", "50.00", "45.00"),
                line("BK1", "CUST1", "CARD", "70.00", "63.00"));

        Map<String, BookingPayment> payments = SquareMonthAggregator.paymentsByBookingId(lines);

        assertThat(payments).containsOnlyKeys("BK1");
        BookingPayment p = payments.get("BK1");
        assertThat(p.channel()).isEqualTo("CARD");
        assertThat(p.collected()).isEqualByComparingTo("108.00");
        assertThat(p.gross()).isEqualByComparingTo("120.00");
    }

    @Test
    @DisplayName("excludes COMP bookings — nothing was actually collected for them")
    void excludesComp() {
        var lines = List.of(line("BK2", "CUST2", "COMP", "50.00", "50.00"));

        Map<String, BookingPayment> payments = SquareMonthAggregator.paymentsByBookingId(lines);

        assertThat(payments).isEmpty();
    }

    @Test
    @DisplayName("keeps CASH, CARD, and CASH-NOTE bookings distinct by channel")
    void distinguishesChannels() {
        var lines = List.of(
                line("BK-CASH", "CUST1", "CASH", "50.00", "50.00"),
                line("BK-CARD", "CUST2", "CARD", "60.00", "54.00"),
                line("BK-NOTE", "CUST3", "CASH-NOTE", "40.00", "40.00"));

        Map<String, BookingPayment> payments = SquareMonthAggregator.paymentsByBookingId(lines);

        assertThat(payments.get("BK-CASH").channel()).isEqualTo("CASH");
        assertThat(payments.get("BK-CARD").channel()).isEqualTo("CARD");
        assertThat(payments.get("BK-NOTE").channel()).isEqualTo("CASH-NOTE");
    }

    @Test
    @DisplayName("ignores lines with no booking id")
    void ignoresLinesWithNoBookingId() {
        var lines = List.of(line(null, "CUST1", "CARD", "50.00", "45.00"));

        Map<String, BookingPayment> payments = SquareMonthAggregator.paymentsByBookingId(lines);

        assertThat(payments).isEmpty();
    }
}
