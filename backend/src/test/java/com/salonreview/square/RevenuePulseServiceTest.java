package com.salonreview.square;

import com.salonreview.domain.SalonConfig;
import com.salonreview.repo.SalonConfigRepository;
import com.salonreview.square.SquareMonthAggregator.AttributedService;
import com.salonreview.square.SquareMonthAggregator.MonthAggregation;
import com.salonreview.web.dto.RevenuePulseDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RevenuePulseService}: card vs cash is derived from the month aggregator's
 * channel attribution (so cash includes CASH-NOTE, matching /overview), and the projection is split
 * by the realized card:cash mix.
 */
class RevenuePulseServiceTest {

    private SquareClient square;
    private RevenueForecastService forecaster;
    private SquareMonthAggregator aggregator;
    private SalonConfigRepository salonConfig;
    private RevenuePulseService service;

    @BeforeEach
    void setUp() {
        square = mock(SquareClient.class);
        forecaster = mock(RevenueForecastService.class);
        aggregator = mock(SquareMonthAggregator.class);
        salonConfig = mock(SalonConfigRepository.class);

        when(square.locationTimeZone()).thenReturn("UTC");
        when(square.bookings(any(), any())).thenReturn(List.of());
        when(salonConfig.findById(1)).thenReturn(Optional.of(SalonConfig.builder()
                .id(1).ownerShortName("o").servicePriceCutoff(new BigDecimal("60.00")).build()));

        service = new RevenuePulseService(square, forecaster, aggregator, salonConfig);
    }

    private static AttributedService svc(String date, String channel, String gross) {
        return new AttributedService("p1", "P", date, "FIRST", "Manicure", new BigDecimal(gross),
                BigDecimal.ZERO, new BigDecimal(gross), BigDecimal.ZERO, true, 1, 1, false, channel,
                null, null, null, null);
    }

    private static MonthAggregation aggOf(int year, int month, List<AttributedService> services) {
        return new MonthAggregation(year, month, "UTC", List.of(), new SquareMonthAggregator.Diag(),
                services, List.of(), List.of());
    }

    @Test
    @DisplayName("cash includes CASH and CASH-NOTE channels; card is the rest; total is their sum")
    void cashIncludesCashNotes() {
        // Past month so the window is the full month (deterministic, no 'today' dependency).
        List<AttributedService> may = List.of(
                svc("2026-05-03", "CARD", "100.00"),
                svc("2026-05-10", "CASH", "40.00"),
                svc("2026-05-12", "CASH-NOTE", "60.00")); // manual cash — the piece the old sum missed
        when(aggregator.aggregate(eq(2026), eq(5), any())).thenReturn(aggOf(2026, 5, may));
        when(aggregator.aggregate(eq(2026), eq(4), any()))
                .thenReturn(aggOf(2026, 4, List.of(svc("2026-04-05", "CARD", "80.00"))));
        when(forecaster.forecast(anyInt(), anyInt(), any(), any()))
                .thenReturn(new ForecastResult(new BigDecimal("200.00"), null, null, 0, 0));

        RevenuePulseDto p = service.pulse(2026, 5);

        assertThat(p.currentCard()).isEqualByComparingTo("100.00");
        assertThat(p.currentCash()).isEqualByComparingTo("100.00"); // 40 cash + 60 cash-note
        assertThat(p.currentGross()).isEqualByComparingTo("200.00");
        assertThat(p.priorCard()).isEqualByComparingTo("80.00");
        assertThat(p.priorCash()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("projection is split by the current period's card:cash ratio")
    void projectionSplitByCurrentMix() {
        // Current month mix 75% card / 25% cash → a $400 forecast splits 300 / 100.
        when(aggregator.aggregate(eq(2026), eq(5), any())).thenReturn(aggOf(2026, 5, List.of(
                svc("2026-05-03", "CARD", "150.00"),
                svc("2026-05-04", "CASH", "50.00"))));
        when(aggregator.aggregate(eq(2026), eq(4), any())).thenReturn(aggOf(2026, 4, List.of()));
        when(forecaster.forecast(anyInt(), anyInt(), any(), any()))
                .thenReturn(new ForecastResult(new BigDecimal("400.00"), null, null, 0, 0));

        RevenuePulseDto p = service.pulse(2026, 5);

        assertThat(p.projectedCard()).isEqualByComparingTo("300.00");
        assertThat(p.projectedCash()).isEqualByComparingTo("100.00");
    }
}
