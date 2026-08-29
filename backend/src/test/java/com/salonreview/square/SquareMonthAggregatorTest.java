package com.salonreview.square;

import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.domain.SalonConfig;
import com.salonreview.repo.OwnerCustomerRepository;
import com.salonreview.repo.SalonConfigRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Covers only the {@link com.salonreview.util.TtlCache} layer added in Phase 2a on top of {@link
 * SquareMonthAggregator#aggregate} — every mock below is stubbed with empty data specifically so
 * the matching/cash-note/discount/comp/suspicious/cancellation pipeline never actually runs; an
 * empty Square response means the three bulk reads (bookings/orders/payments) are the only
 * collaborators whose call count matters for these tests. End-to-end behavioral coverage of the
 * pipeline itself belongs in a later, separate test class (Phase 2e). */
class SquareMonthAggregatorTest {

    private SquareClientProvider squareClientProvider;
    private SquareClient square;
    private CurrentBusinessContext currentBusinessContext;
    private com.salonreview.repo.SquareBookingMirrorRepository bookingMirrorRepository;
    private com.salonreview.config.SquareMirrorProperties mirrorProperties;
    private SquareMonthAggregator aggregator;

    @BeforeEach
    void setUp() {
        squareClientProvider = mock(SquareClientProvider.class);
        square = mock(SquareClient.class);
        when(squareClientProvider.forBusiness(1L)).thenReturn(square);
        CashNoteParser cashNotes = new CashNoteParser();
        OwnerCustomerRepository ownerCustomers = mock(OwnerCustomerRepository.class);
        when(ownerCustomers.findAllByBusinessId(anyLong())).thenReturn(List.of());
        currentBusinessContext = mock(CurrentBusinessContext.class);
        when(currentBusinessContext.id()).thenReturn(1L);
        SalonConfigRepository salonConfig = mock(SalonConfigRepository.class);
        SalonConfig sc = SalonConfig.builder().businessId(1L).ownerShortName("o")
                .tierServiceThreshold(0).servicePriceCutoff(BigDecimal.ZERO)
                .baseCommissionRate(BigDecimal.ZERO).tierCommissionRate(BigDecimal.ZERO)
                .cardTipFeeRate(BigDecimal.ZERO).tierEnabled(false).build();
        when(salonConfig.findByBusinessId(1L)).thenReturn(Optional.of(sc));

        when(square.allTeamMembers()).thenReturn(List.of());
        when(square.bookings(any(), any())).thenReturn(List.of());
        when(square.completedOrders(any(), any())).thenReturn(List.of());
        when(square.payments(any(), any())).thenReturn(List.of());
        when(square.canonicalCustomerIds(any())).thenReturn(Map.of());
        when(square.catalogPrices(any())).thenReturn(Map.of());
        when(square.customerNames(any())).thenReturn(Map.of());

        bookingMirrorRepository = mock(com.salonreview.repo.SquareBookingMirrorRepository.class);
        when(bookingMirrorRepository.findByBusinessIdAndStartAtBetween(any(), any(), any())).thenReturn(List.of());
        var orderMirrorRepository = mock(com.salonreview.repo.SquareOrderMirrorRepository.class);
        when(orderMirrorRepository.findByBusinessIdAndClosedAtBetween(any(), any(), any())).thenReturn(List.of());
        var paymentMirrorRepository = mock(com.salonreview.repo.SquarePaymentMirrorRepository.class);
        when(paymentMirrorRepository.findByBusinessIdAndCreatedAtBetween(any(), any(), any())).thenReturn(List.of());
        mirrorProperties = mock(com.salonreview.config.SquareMirrorProperties.class);

        aggregator = new SquareMonthAggregator(squareClientProvider, cashNotes, ownerCustomers,
                currentBusinessContext, salonConfig,
                bookingMirrorRepository, orderMirrorRepository, paymentMirrorRepository, mirrorProperties);
    }

    @Test
    @DisplayName("Phase 2i cutover: when SquareMirrorProperties.aggregateEnabled is true, aggregate() reads the mirror instead of live Square")
    void aggregateUsesMirrorWhenCutoverFlagEnabled() {
        when(mirrorProperties.isAggregateEnabled()).thenReturn(true);

        aggregator.aggregate(2026, 7, BigDecimal.ZERO);

        verify(bookingMirrorRepository).findByBusinessIdAndStartAtBetween(any(), any(), any());
        verify(square, times(0)).bookings(any(), any());
    }

    @Test
    @DisplayName("Phase 2i emergency fallback: when SquareMirrorProperties.aggregateEnabled is false, aggregate() still reads live Square")
    void aggregateUsesLiveWhenCutoverFlagDisabled() {
        when(mirrorProperties.isAggregateEnabled()).thenReturn(false);

        aggregator.aggregate(2026, 7, BigDecimal.ZERO);

        verify(square).bookings(any(), any());
        verify(bookingMirrorRepository, times(0)).findByBusinessIdAndStartAtBetween(any(), any(), any());
    }

    @Test
    @DisplayName("a second aggregate() call for the same (business, month, cutoff) within the TTL reuses the cached result")
    void aggregateCachesWithinTtl() {
        SquareMonthAggregator.MonthAggregation first = aggregator.aggregate(2026, 7, BigDecimal.ZERO);
        SquareMonthAggregator.MonthAggregation second = aggregator.aggregate(2026, 7, BigDecimal.ZERO);

        assertThat(second).isSameAs(first);
        verify(square, times(1)).bookings(any(), any());
        verify(square, times(1)).completedOrders(any(), any());
        verify(square, times(1)).payments(any(), any());
    }

    @Test
    @DisplayName("invalidateCache() forces the next aggregate() call to recompute")
    void invalidateCacheForcesRecompute() {
        aggregator.aggregate(2026, 7, BigDecimal.ZERO);
        aggregator.invalidateCache();
        aggregator.aggregate(2026, 7, BigDecimal.ZERO);

        verify(square, times(2)).bookings(any(), any());
    }

    @Test
    @DisplayName("a different month is fetched independently, never served from another month's cache entry")
    void differentMonthIsNotCrossServed() {
        aggregator.aggregate(2026, 7, BigDecimal.ZERO);
        aggregator.aggregate(2026, 8, BigDecimal.ZERO);

        verify(square, times(2)).bookings(any(), any());
    }

    @Test
    @DisplayName("a different price cutoff for the same month is treated as a distinct cache entry — the cutoff affects counted-service totals")
    void differentCutoffIsNotCrossServed() {
        aggregator.aggregate(2026, 7, BigDecimal.ZERO);
        aggregator.aggregate(2026, 7, new BigDecimal("60.00"));

        verify(square, times(2)).bookings(any(), any());
    }

    @Test
    @DisplayName("invalidateCache() only drops the calling business's own cached entries, never another business's")
    void invalidateCacheOnlyDropsCallingBusinesssOwnEntry() {
        SquareClient square2 = mock(SquareClient.class);
        when(squareClientProvider.forBusiness(2L)).thenReturn(square2);
        when(square2.allTeamMembers()).thenReturn(List.of());
        when(square2.bookings(any(), any())).thenReturn(List.of());
        when(square2.completedOrders(any(), any())).thenReturn(List.of());
        when(square2.payments(any(), any())).thenReturn(List.of());
        when(square2.canonicalCustomerIds(any())).thenReturn(Map.of());
        when(square2.catalogPrices(any())).thenReturn(Map.of());
        when(square2.customerNames(any())).thenReturn(Map.of());

        when(currentBusinessContext.id()).thenReturn(1L);
        aggregator.aggregate(2026, 7, BigDecimal.ZERO);
        when(currentBusinessContext.id()).thenReturn(2L);
        aggregator.aggregate(2026, 7, BigDecimal.ZERO);
        verify(square, times(1)).bookings(any(), any());
        verify(square2, times(1)).bookings(any(), any());

        when(currentBusinessContext.id()).thenReturn(1L);
        aggregator.invalidateCache(); // only business 1's entry should drop

        aggregator.aggregate(2026, 7, BigDecimal.ZERO); // business 1: cache miss -> recomputes
        when(currentBusinessContext.id()).thenReturn(2L);
        aggregator.aggregate(2026, 7, BigDecimal.ZERO); // business 2: still cached -> must NOT recompute

        verify(square, times(2)).bookings(any(), any());
        verify(square2, times(1)).bookings(any(), any());
    }
}
