package com.salonreview.timecards;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonreview.timecards.TimecardTypes.AppointmentStatus;
import com.salonreview.timecards.TimecardTypes.BreakKind;
import com.salonreview.timecards.TimecardTypes.DraftAppointment;
import com.salonreview.timecards.TimecardTypes.FindingCode;
import com.salonreview.timecards.TimecardTypes.TimeSpan;
import com.salonreview.timecards.TimecardTypes.TimecardDraft;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TimecardDraftCalculatorTest {

    private static final CaWageRules RULES = CaWageRules.defaults();
    private static final TimecardDraftCalculator CALC = new TimecardDraftCalculator(RULES);
    private static final LocalDate D = LocalDate.of(2026, 10, 12);

    private static java.time.Instant at(String hhmm) {
        return LocalDateTime.of(D, LocalTime.parse(hhmm)).atZone(RULES.zone()).toInstant();
    }

    private static DraftAppointment appt(String a, String b, AppointmentStatus s) {
        return new DraftAppointment(new TimeSpan(at(a), at(b)), s);
    }

    private static DraftAppointment ok(String a, String b) {
        return appt(a, b, AppointmentStatus.ACCEPTED);
    }

    @Test
    @DisplayName("back-to-back appointments get padding only at the outer edges")
    void backToBack() {
        TimecardDraft d = CALC.derive(D, List.of(ok("10:00", "11:00"), ok("11:00", "12:00"))).orElseThrow();
        assertThat(d.timecard().span()).isEqualTo(new TimeSpan(at("09:45"), at("12:15")));
        assertThat(d.timecard().breaks()).isEmpty();
    }

    @Test
    @DisplayName("gaps become unpaid breaks")
    void gapIsUnpaid() {
        TimecardDraft d = CALC.derive(D, List.of(ok("10:00", "11:00"), ok("14:00", "15:00"))).orElseThrow();
        assertThat(d.timecard().breaks()).hasSize(1);
        assertThat(d.timecard().breaks().get(0).span()).isEqualTo(new TimeSpan(at("11:15"), at("13:45")));
        assertThat(d.timecard().breaks().get(0).kind()).isEqualTo(BreakKind.UNPAID);
        assertThat(d.notes()).doesNotContain(FindingCode.MEAL_NOT_POSSIBLE);
    }

    @Test
    @DisplayName("no-show credits 30 minutes, cancelled is unpaid")
    void noShowAndCancelled() {
        TimecardDraft d = CALC.derive(D, List.of(appt("10:00", "11:00", AppointmentStatus.NO_SHOW),
                appt("13:00", "14:00", AppointmentStatus.CANCELLED))).orElseThrow();
        assertThat(d.timecard().span()).isEqualTo(new TimeSpan(at("09:45"), at("10:15")));
        assertThat(CALC.derive(D, List.of(appt("13:00", "14:00", AppointmentStatus.CANCELLED)))).isEmpty();
    }

    @Test
    @DisplayName("continuous long day gets two paid rests and a MEAL_NOT_POSSIBLE note")
    void longDay() {
        TimecardDraft d = CALC.derive(D, List.of(ok("10:00", "17:00"))).orElseThrow();
        assertThat(d.notes()).contains(FindingCode.MEAL_NOT_POSSIBLE);
        long rests = d.timecard().breaks().stream().filter(b -> b.kind() == BreakKind.PAID_REST).count();
        assertThat(rests).isEqualTo(2);
        d.timecard().breaks().forEach(b -> {
            assertThat(b.span().minutes()).isEqualTo(10);
            assertThat(b.span().start()).isAfterOrEqualTo(d.timecard().span().start());
            assertThat(b.span().end()).isBeforeOrEqualTo(d.timecard().span().end());
        });
    }

    @Test
    @DisplayName("short day has no breaks to pre-fill")
    void shortDay() {
        TimecardDraft d = CALC.derive(D, List.of(ok("10:00", "12:00"))).orElseThrow();
        assertThat(d.timecard().breaks()).isEmpty();
        assertThat(d.notes()).isEmpty();
    }
}
