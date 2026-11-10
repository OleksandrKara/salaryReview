package com.salonreview.timecards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.salonreview.timecards.TimecardDayService.DayCalc;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class TimecardVoidTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 5);
    private JdbcTemplate jdbc;
    private TimecardDayService days;
    private TimecardReviewService svc;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        days = mock(TimecardDayService.class);
        svc = new TimecardReviewService(jdbc, days, null, null, null) {
            @Override long ensureReview(Long b, Long p, LocalDate d, String mode) { return 7L; }
        };
        when(jdbc.queryForList(anyString(), eq(String.class), any(Object[].class))).thenReturn(List.of("W2"));
    }

    private DayCalc calc(String source, String status) {
        return new DayCalc(DAY, 43L, source, List.of(), List.of(), null, 1850, null, status, false, false);
    }

    @Test
    void workerCannotWithdrawSquareDay() {
        when(days.calc(1L, 43L, DAY)).thenReturn(calc("SQUARE", "APPROVED"));
        assertThrows(ResponseStatusException.class, () -> svc.withdraw(1L, 43L, "2026-11-05", "test"));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void workerCannotWithdrawApprovedManualDay() {
        when(days.calc(1L, 43L, DAY)).thenReturn(calc("DRAFT", "APPROVED"));
        assertThrows(ResponseStatusException.class, () -> svc.withdraw(1L, 43L, "2026-11-05", "test"));
    }

    @Test
    void reasonIsRequired() {
        when(days.calc(1L, 43L, DAY)).thenReturn(calc("DRAFT", "WORKER_CONFIRMED"));
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> svc.withdraw(1L, 43L, "2026-11-05", "  "));
        assertEquals(400, e.getStatusCode().value());
    }

    @Test
    void ownerVoidsUnapprovedManualDay() {
        when(days.calc(1L, 43L, DAY)).thenReturn(calc("DRAFT", "WORKER_CONFIRMED"));
        svc.voidDay(1L, 31L, "OWNER", 43L, DAY, "Test shift");
        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("status = 'VOIDED'"), any(Object[].class));
    }

    @Test
    void squareDayCannotBeVoided() {
        when(days.calc(1L, 43L, DAY)).thenReturn(calc("SQUARE", "NEEDS_REVIEW"));
        assertThrows(ResponseStatusException.class, () -> svc.voidDay(1L, 31L, "OWNER", 43L, DAY, "x"));
    }
}
