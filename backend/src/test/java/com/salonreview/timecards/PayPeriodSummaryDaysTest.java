package com.salonreview.timecards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.salonreview.square.SettlementPreviewService;
import com.salonreview.timecards.PayPeriodSummaryService.DayLine;
import com.salonreview.timecards.PayPeriodSummaryService.ProviderSummary;
import com.salonreview.timecards.TimecardDayService.DayCalc;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class PayPeriodSummaryDaysTest {

    private DayCalc calc(long paid, String status) {
        TimecardTypes.DayAnalysis a = mock(TimecardTypes.DayAnalysis.class);
        when(a.paidMinutes()).thenReturn(paid);
        when(a.findings()).thenReturn(List.of());
        return new DayCalc(LocalDate.of(2026, 11, 9), 5L, "SQUARE", List.of(), List.of(), a, 2000, null, status, false, false, false);
    }

    @Test
    void dayLinesAddUpToPeriodTotals() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(String.class), any(Object[].class))).thenReturn("Lena");
        TimecardDayService days = mock(TimecardDayService.class);
        SettlementPreviewService previews = mock(SettlementPreviewService.class);
        DayCalc zero = new DayCalc(LocalDate.of(2026, 11, 9), 5L, "NONE", List.of(), List.of(), null, 2000, null, "NONE", false, false, false);
        DayCalc d1 = calc(600, "APPROVED");
        DayCalc d2 = calc(480, "PENDING");
        when(days.calc(anyLong(), anyLong(), any(LocalDate.class))).thenReturn(zero);
        when(days.calc(1L, 5L, LocalDate.of(2026, 11, 9))).thenReturn(d1);
        when(days.calc(1L, 5L, LocalDate.of(2026, 11, 10))).thenReturn(d2);
        PayPeriodSummaryService svc = new PayPeriodSummaryService(jdbc, days, previews);

        ProviderSummary s = svc.forProvider(1L, 5L, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 15), 2026, 11, 1, false);

        List<DayLine> lines = s.days();
        assertEquals(2, lines.size());
        assertEquals(16000 + 6000, lines.get(0).dayTotalCents());
        assertEquals(120, lines.get(0).overtimeMinutes());
        assertEquals(16000, lines.get(1).dayTotalCents());
        assertEquals(s.grossWagesCents(), lines.stream().mapToInt(DayLine::dayTotalCents).sum());
        assertEquals(38000, s.grossWagesCents());
        assertEquals(1, s.unapprovedDays());
    }
}
