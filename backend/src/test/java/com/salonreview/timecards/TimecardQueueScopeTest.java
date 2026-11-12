package com.salonreview.timecards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.salonreview.config.AppUserPrincipal;
import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.domain.Role;
import com.salonreview.timecards.TimecardDtos.DayView;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class TimecardQueueScopeTest {

    private TimecardDayService days;
    private TimecardController controller;
    private AppUserPrincipal me;

    private DayView day(String status) {
        DayView v = mock(DayView.class);
        when(v.source()).thenReturn("SQUARE");
        when(v.status()).thenReturn(status);
        return v;
    }

    @BeforeEach
    void setUp() {
        CurrentBusinessContext business = mock(CurrentBusinessContext.class);
        when(business.id()).thenReturn(1L);
        days = mock(TimecardDayService.class);
        TimecardReviewService reviews = mock(TimecardReviewService.class);
        when(reviews.canApprove(anyLong(), anyLong(), anyString(), anyLong())).thenReturn(true);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(Long.class), any(Object[].class))).thenReturn(List.of(43L));
        controller = new TimecardController(business, days, reviews, null, null, jdbc);
        me = mock(AppUserPrincipal.class);
        when(me.getRole()).thenReturn(Role.MANAGER);
        when(me.getUserId()).thenReturn(7L);
        DayView approved = day("APPROVED");
        when(days.view(anyLong(), anyLong(), any(LocalDate.class), anyBoolean())).thenReturn(approved);
    }

    @Test
    void defaultQueueHidesApprovedDays() {
        assertEquals(0, controller.queue(me, 3, "pending").size());
    }

    @Test
    void scopeAllReturnsApprovedDays() {
        assertEquals(3, controller.queue(me, 3, "all").size());
    }

    @Test
    void windowIsCappedAtSixtyDays() {
        assertEquals(60, controller.queue(me, 500, "all").size());
    }
}
