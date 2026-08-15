package com.salonreview.square;

import com.salonreview.repo.OwnerCustomerRepository;
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
 * Square can silently merge two duplicate customer profiles for the same real person into one
 * canonical id. An already-written Order permanently keeps whichever id was current when it was
 * created, while the Booking for that same visit can carry the other id — so a real, paid visit's
 * order and booking can carry two different, never-equal customer ids. Without resolving both
 * through Square's canonical id first, the order-to-booking matcher (keyed on customer + service)
 * can never find the booking, and the whole paid order silently falls into Unattributed sales even
 * though the money was genuinely collected. See the real production case: a client's booking
 * carried the post-merge id, but her paid order still carried the old, pre-merge id.
 */
class CustomerMergeAttributionTest {

    private static final String OLD_CUSTOMER_ID = "OLD-PRE-MERGE-ID";
    private static final String CANONICAL_CUSTOMER_ID = "NEW-CANONICAL-ID";
    private static final String PROVIDER = "TM-LESYA";

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
        aggregator = new SquareMonthAggregator(squareClientProvider, new CashNoteParser(), ownerRepo, currentBusinessContext);
        when(square.locationTimeZone()).thenReturn("UTC");
        when(square.allTeamMembers())
                .thenReturn(List.of(new TeamMember(PROVIDER, "Lesya", "Petrova", "ACTIVE", false, null, null)));
        when(ownerRepo.findAllByBusinessId(1L)).thenReturn(List.of());
    }

    private static Booking booking(String customerId) {
        var seg = new AppointmentSegment(PROVIDER, "VAR-MANICURE", 120);
        return new Booking("bk1", "ACCEPTED", "2026-08-02T18:00:00Z", null, "2026-08-02T19:49:51Z",
                "LOC", customerId, null, null, List.of(seg));
    }

    private static Order order(String customerId) {
        Money g = new Money(10900L, "USD");
        var li = new OrderLineItem("u1", "Russian Gel-Overlay Manicure", "1", "VAR-MANICURE", null, g, g, null);
        return new Order("o1", "LOC", customerId, "COMPLETED", "2026-08-02T19:17:36Z",
                "2026-08-02T19:17:35Z", List.of(li), null, null, null, null);
    }

    @Test
    @DisplayName("Order under the pre-merge id still matches a booking under the canonical id, once resolved")
    void mergedCustomerIdsStillMatch() {
        when(square.bookings(any(), any())).thenReturn(List.of(booking(CANONICAL_CUSTOMER_ID)));
        when(square.completedOrders(any(), any())).thenReturn(List.of(order(OLD_CUSTOMER_ID)));
        when(square.catalogPrices(any())).thenReturn(Map.of("VAR-MANICURE", new BigDecimal("109.00")));
        // Square's live customer lookup resolves both the old and the canonical id to the same person.
        when(square.canonicalCustomerIds(any())).thenReturn(Map.of(
                OLD_CUSTOMER_ID, CANONICAL_CUSTOMER_ID,
                CANONICAL_CUSTOMER_ID, CANONICAL_CUSTOMER_ID));

        MonthAggregation agg = aggregator.aggregate(2026, 8, new BigDecimal("50.00"));

        assertThat(agg.unmatched()).isEmpty();
        assertThat(agg.services()).hasSize(1);
        assertThat(agg.services().get(0).providerId()).isEqualTo(PROVIDER);
        assertThat(agg.services().get(0).gross()).isEqualByComparingTo("109.00");
    }

    @Test
    @DisplayName("Without id resolution (unstubbed canonicalCustomerIds), the mismatched order falls to Unattributed")
    void unresolvedMergeFallsToUnmatched() {
        when(square.bookings(any(), any())).thenReturn(List.of(booking(CANONICAL_CUSTOMER_ID)));
        when(square.completedOrders(any(), any())).thenReturn(List.of(order(OLD_CUSTOMER_ID)));
        when(square.catalogPrices(any())).thenReturn(Map.of("VAR-MANICURE", new BigDecimal("109.00")));
        // canonicalCustomerIds left unstubbed — Mockito's default answer returns an empty map, so ids
        // pass through unresolved, reproducing the bug this test guards against.

        MonthAggregation agg = aggregator.aggregate(2026, 8, new BigDecimal("50.00"));

        assertThat(agg.services()).isEmpty();
        assertThat(agg.unmatched()).hasSize(1);
    }
}
