package com.salonreview.square;

import com.salonreview.commission.HalfInput;
import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.square.SquareMonthAggregator.AttributedService;
import com.salonreview.square.SquareMonthAggregator.Diag;
import com.salonreview.square.SquareMonthAggregator.MonthAggregation;
import com.salonreview.square.SquareMonthAggregator.ProviderMonth;
import com.salonreview.square.SquareMonthAggregatorShadowDiffService.ShadowDiffResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Milestone 2g: verifies the shadow-diff comparator itself surfaces every kind of discrepancy it's
 * meant to catch (per-provider half totals, extra/missing service lines, diag counters) and reports
 * clean when live and mirror genuinely agree — before it's ever trusted to run against real data.
 */
class SquareMonthAggregatorShadowDiffServiceTest {

    private SquareMonthAggregator aggregator;
    private SquareMonthAggregatorShadowDiffService shadowDiff;

    @BeforeEach
    void setUp() {
        aggregator = mock(SquareMonthAggregator.class);
        CurrentBusinessContext currentBusinessContext = new CurrentBusinessContext();
        shadowDiff = new SquareMonthAggregatorShadowDiffService(aggregator, currentBusinessContext);
    }

    private static ProviderMonth provider(String id, HalfInput first, HalfInput second) {
        return new ProviderMonth(id, "Provider " + id, first, second);
    }

    private static HalfInput half(int counted, String cardRevenue) {
        return new HalfInput(counted, new BigDecimal(cardRevenue), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private static AttributedService line(String providerId, String bookingId, String amount) {
        return new AttributedService(providerId, "Provider " + providerId, "2026-05-10", "FIRST", "Manicure",
                new BigDecimal(amount), BigDecimal.ZERO, new BigDecimal(amount), BigDecimal.ZERO, true, 1, 1,
                false, "CARD", "10:00", bookingId, "CUST1", null);
    }

    private static MonthAggregation agg(List<ProviderMonth> providers, List<AttributedService> services) {
        return new MonthAggregation(2026, 5, "UTC", providers, new Diag(), services,
                List.of(), List.of(), List.of(), List.of());
    }

    @Test
    @DisplayName("identical live and mirror results → clean, no discrepancies")
    void identicalResultsAreClean() {
        MonthAggregation same = agg(List.of(provider("TM1", half(1, "100.00"), half(0, "0.00"))),
                List.of(line("TM1", "bk1", "100.00")));
        when(aggregator.aggregate(2026, 5, BigDecimal.ZERO)).thenReturn(same);
        when(aggregator.aggregateFromMirror(2026, 5, BigDecimal.ZERO)).thenReturn(same);

        ShadowDiffResult result = shadowDiff.diff(1L, 2026, 5, BigDecimal.ZERO);

        assertThat(result.clean()).isTrue();
        assertThat(result.discrepancies()).isEmpty();
    }

    @Test
    @DisplayName("a provider's half totals differ between live and mirror → reported, not silently passed")
    void differingHalfTotalsAreReported() {
        MonthAggregation live = agg(List.of(provider("TM1", half(1, "100.00"), half(0, "0.00"))), List.of());
        MonthAggregation mirror = agg(List.of(provider("TM1", half(1, "90.00"), half(0, "0.00"))), List.of());
        when(aggregator.aggregate(2026, 5, BigDecimal.ZERO)).thenReturn(live);
        when(aggregator.aggregateFromMirror(2026, 5, BigDecimal.ZERO)).thenReturn(mirror);

        ShadowDiffResult result = shadowDiff.diff(1L, 2026, 5, BigDecimal.ZERO);

        assertThat(result.clean()).isFalse();
        assertThat(result.discrepancies()).anyMatch(d -> d.contains("TM1") && d.contains("first half differs"));
    }

    @Test
    @DisplayName("a provider present only in the mirror result is reported, not dropped")
    void providerOnlyInMirrorIsReported() {
        MonthAggregation live = agg(List.of(), List.of());
        MonthAggregation mirror = agg(List.of(provider("TM2", half(1, "50.00"), half(0, "0.00"))), List.of());
        when(aggregator.aggregate(2026, 5, BigDecimal.ZERO)).thenReturn(live);
        when(aggregator.aggregateFromMirror(2026, 5, BigDecimal.ZERO)).thenReturn(mirror);

        ShadowDiffResult result = shadowDiff.diff(1L, 2026, 5, BigDecimal.ZERO);

        assertThat(result.clean()).isFalse();
        assertThat(result.discrepancies()).anyMatch(d -> d.contains("TM2") && d.contains("only in MIRROR"));
    }

    @Test
    @DisplayName("an extra service line on one side is reported as a list discrepancy")
    void extraServiceLineIsReported() {
        MonthAggregation live = agg(List.of(), List.of(line("TM1", "bk1", "100.00")));
        MonthAggregation mirror = agg(List.of(), List.of(line("TM1", "bk1", "100.00"), line("TM1", "bk2", "50.00")));
        when(aggregator.aggregate(2026, 5, BigDecimal.ZERO)).thenReturn(live);
        when(aggregator.aggregateFromMirror(2026, 5, BigDecimal.ZERO)).thenReturn(mirror);

        ShadowDiffResult result = shadowDiff.diff(1L, 2026, 5, BigDecimal.ZERO);

        assertThat(result.clean()).isFalse();
        assertThat(result.discrepancies()).anyMatch(d -> d.contains("services") && d.contains("only in MIRROR") && d.contains("bk2"));
    }

    @Test
    @DisplayName("a differing diag counter is reported by name")
    void differingDiagCounterIsReported() {
        Diag liveDiag = new Diag();
        liveDiag.ownerComps = 2;
        Diag mirrorDiag = new Diag();
        mirrorDiag.ownerComps = 1;
        MonthAggregation live = new MonthAggregation(2026, 5, "UTC", List.of(), liveDiag, List.of(), List.of(), List.of(), List.of(), List.of());
        MonthAggregation mirror = new MonthAggregation(2026, 5, "UTC", List.of(), mirrorDiag, List.of(), List.of(), List.of(), List.of(), List.of());
        when(aggregator.aggregate(2026, 5, BigDecimal.ZERO)).thenReturn(live);
        when(aggregator.aggregateFromMirror(2026, 5, BigDecimal.ZERO)).thenReturn(mirror);

        ShadowDiffResult result = shadowDiff.diff(1L, 2026, 5, BigDecimal.ZERO);

        assertThat(result.clean()).isFalse();
        assertThat(result.discrepancies()).anyMatch(d -> d.contains("diag.ownerComps") && d.contains("live=2") && d.contains("mirror=1"));
    }
}
