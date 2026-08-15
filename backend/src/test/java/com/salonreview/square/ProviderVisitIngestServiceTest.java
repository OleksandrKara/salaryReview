package com.salonreview.square;

import com.salonreview.domain.ProviderVisit;
import com.salonreview.domain.SalonConfig;
import com.salonreview.repo.ProviderVisitRepository;
import com.salonreview.repo.SalonConfigRepository;
import com.salonreview.square.SquareMonthAggregator.AttributedService;
import com.salonreview.square.SquareMonthAggregator.MonthAggregation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link ProviderVisitIngestService}: dedupe to one visit per day, idempotent re-ingest. */
class ProviderVisitIngestServiceTest {

    private SquareMonthAggregator aggregator;
    private SquareClient square;
    private ProviderVisitRepository repo;
    private ProviderVisitIngestService service;

    @BeforeEach
    void setUp() {
        aggregator = mock(SquareMonthAggregator.class);
        square = mock(SquareClient.class);
        SalonConfigRepository salonConfig = mock(SalonConfigRepository.class);
        repo = mock(ProviderVisitRepository.class);

        when(square.locationTimeZone()).thenReturn("UTC");
        when(square.bookings(any(), any())).thenReturn(List.of()); // no rebookings in this fixture
        com.salonreview.config.CurrentBusinessContext currentBusinessContext =
                mock(com.salonreview.config.CurrentBusinessContext.class);
        when(currentBusinessContext.id()).thenReturn(1L);
        when(salonConfig.findByBusinessId(1L)).thenReturn(Optional.of(SalonConfig.builder()
                .id(1).ownerShortName("o").servicePriceCutoff(new BigDecimal("60.00")).build()));

        service = new ProviderVisitIngestService(aggregator, square, salonConfig, repo, currentBusinessContext);
    }

    private static AttributedService svc(String customer, String provider, String date) {
        return new AttributedService(provider, "Alice", date, "FIRST", "Manicure", new BigDecimal("80"),
                BigDecimal.ZERO, new BigDecimal("80"), BigDecimal.ZERO, true, 1, 1, false, "CARD",
                null, null, customer, null);
    }

    @Test
    @DisplayName("collapses multiple same-day services to one visit; skips anonymous; replaces the month")
    void dedupeAndReplace() {
        when(aggregator.aggregate(eq(2026), eq(5), any())).thenReturn(new MonthAggregation(
                2026, 5, "UTC", List.of(), new SquareMonthAggregator.Diag(),
                List.of(
                        svc("C1", "P1", "2026-05-03"),   // two services, same customer/provider/day
                        svc("C1", "P1", "2026-05-03"),   // → one visit
                        svc("C2", "P1", "2026-05-04"),
                        svc(null, "P1", "2026-05-05")),  // anonymous → skipped
                List.of(), List.of()));

        int n = service.ingestMonth(2026, 5);

        assertThat(n).isEqualTo(2); // C1/2026-05-03 and C2/2026-05-04
        verify(repo).deleteByServiceDateBetween(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31));
        ArgumentCaptor<List<ProviderVisit>> cap = ArgumentCaptor.forClass(List.class);
        verify(repo).saveAll(cap.capture());
        assertThat(cap.getValue()).extracting(ProviderVisit::getCustomerId).containsExactlyInAnyOrder("C1", "C2");
    }

    @Test
    @DisplayName("rebook detection still matches when the rebooking's own raw booking carries a stale pre-merge id")
    void rebookDetectionSurvivesStaleMergedId() {
        // agg.services() reports the visit under the canonical customer id (SquareMonthAggregator's own
        // resolution). The candidate rebooking below is fetched independently, straight from Square, and
        // still carries the OLD pre-merge id — exactly like a real booking created before a later merge.
        // Without resolving this index to the same canonical id space, the join below would silently
        // miss the rebook.
        String canonicalId = "CANON-ID";
        String staleId = "STALE-PRE-MERGE-ID";
        when(aggregator.aggregate(eq(2026), eq(5), any())).thenReturn(new MonthAggregation(
                2026, 5, "UTC", List.of(), new SquareMonthAggregator.Diag(),
                List.of(svc(canonicalId, "P1", "2026-05-03")),
                List.of(), List.of()));
        var futureBooking = new SquareClient.Booking("bk-future", "ACCEPTED",
                "2026-05-20T10:00:00Z", "2026-05-03T09:00:00Z", null, "LOC", staleId, null, null, List.of());
        when(square.bookings(any(), any())).thenReturn(List.of(futureBooking));
        when(square.canonicalCustomerIds(any())).thenReturn(Map.of(staleId, canonicalId));

        service.ingestMonth(2026, 5);

        ArgumentCaptor<List<ProviderVisit>> cap = ArgumentCaptor.forClass(List.class);
        verify(repo).saveAll(cap.capture());
        assertThat(cap.getValue()).hasSize(1);
        assertThat(cap.getValue().get(0).isRebookedSameDay()).isTrue();
    }

    @Test
    @DisplayName("backfill skips a month that already has visits")
    void backfillSkipsPopulated() {
        when(repo.countByServiceDateBetween(any(), any())).thenReturn(5L); // every month already populated

        service.backfillHistory(3);

        verify(aggregator, org.mockito.Mockito.never()).aggregate(anyInt(), anyInt(), any());
    }
}
