package com.salonreview.square;

import com.salonreview.square.SquareMonthAggregator.Seg;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The 4-hands tie-break: when one checkout's order line has several candidate bookings (same customer +
 * service, e.g. two providers on a 4-hands visit), the payment belongs to the booking Square stamped at
 * checkout time. We identify it by the booking whose {@code updated_at} is closest to the order's close
 * time — never by parsing the appointment note.
 */
class CheckoutAttributionTest {

    private static Seg seg(String provider, LocalDate day, String updatedAt) {
        return new Seg(provider, day, provider + "-booking", day.toString() + "T11:00:00Z",
                updatedAt == null ? null : Instant.parse(updatedAt));
    }

    @Test
    @DisplayName("Same-day siblings: the booking checked out (updated_at == order close) wins (Anna, not Terresea)")
    void checkedOutBookingWins() {
        // Real booking t9x6jxumox5bts (Anna, the pedicure + checkout) vs 215l79ohl8eob5 (Terresea, the
        // manicure). Both bookings are May 22; the $230 order closed 20:09:22 / updated 20:09:23.
        LocalDate visit = LocalDate.of(2026, 5, 22);
        Instant checkoutAt = Instant.parse("2026-05-22T20:09:22.387Z");

        Seg terresea = seg("ANNA-or-TERRESEA-order-on-list-first", visit, "2026-05-22T20:20:04Z"); // edited later
        Seg anna = seg("ANNA", visit, "2026-05-22T20:09:23Z");                                       // checked out

        // Terresea is first in the list (Square's return order) — old logic would have picked her.
        Seg picked = SquareMonthAggregator.nearestUnused(List.of(terresea, anna), visit, checkoutAt, 2);

        assertThat(picked).isSameAs(anna);
    }

    @Test
    @DisplayName("Order of candidates does not matter — checkout skew decides, not list position")
    void orderIndependent() {
        LocalDate visit = LocalDate.of(2026, 5, 22);
        Instant checkoutAt = Instant.parse("2026-05-22T20:09:22Z");
        Seg anna = seg("ANNA", visit, "2026-05-22T20:09:23Z");
        Seg terresea = seg("TERRESEA", visit, "2026-05-22T20:20:04Z");

        assertThat(SquareMonthAggregator.nearestUnused(List.of(anna, terresea), visit, checkoutAt, 2)).isSameAs(anna);
        assertThat(SquareMonthAggregator.nearestUnused(List.of(terresea, anna), visit, checkoutAt, 2)).isSameAs(anna);
    }

    @Test
    @DisplayName("Day proximity stays primary: a closer day beats a smaller checkout skew")
    void dayProximityIsPrimary() {
        LocalDate orderDay = LocalDate.of(2026, 5, 22);
        Instant checkoutAt = Instant.parse("2026-05-22T20:09:22Z");
        // sameDay was touched far from checkout; offByTwo was touched right at checkout but is 2 days away.
        Seg sameDay = seg("SAME_DAY", orderDay, "2026-05-01T00:00:00Z");
        Seg offByTwo = seg("OFF_BY_TWO", orderDay.minusDays(2), "2026-05-22T20:09:22Z");

        assertThat(SquareMonthAggregator.nearestUnused(List.of(offByTwo, sameDay), orderDay, checkoutAt, 2))
                .isSameAs(sameDay);
    }

    @Test
    @DisplayName("A single booking matches regardless of timestamps (normal one-provider visit)")
    void singleCandidate() {
        LocalDate visit = LocalDate.of(2026, 5, 22);
        Seg only = seg("SOLO", visit, null);
        assertThat(SquareMonthAggregator.nearestUnused(List.of(only), visit, Instant.parse("2026-05-22T20:09:22Z"), 2))
                .isSameAs(only);
    }

    @Test
    @DisplayName("Nothing within the window returns null (off-day payment → unmatched for review)")
    void noneInWindow() {
        LocalDate orderDay = LocalDate.of(2026, 5, 22);
        Seg farAway = seg("FAR", orderDay.minusDays(10), "2026-05-12T10:00:00Z");
        assertThat(SquareMonthAggregator.nearestUnused(List.of(farAway), orderDay, null, 2)).isNull();
    }

    @Test
    @DisplayName("A used segment is never re-picked (no double-assignment)")
    void skipsUsed() {
        LocalDate visit = LocalDate.of(2026, 5, 22);
        Instant checkoutAt = Instant.parse("2026-05-22T20:09:22Z");
        Seg anna = seg("ANNA", visit, "2026-05-22T20:09:23Z");
        anna.used = true;
        Seg terresea = seg("TERRESEA", visit, "2026-05-22T20:20:04Z");

        // Anna already consumed by the first line → the second identical line falls to Terresea.
        assertThat(SquareMonthAggregator.nearestUnused(List.of(anna, terresea), visit, checkoutAt, 2))
                .isSameAs(terresea);
    }
}
