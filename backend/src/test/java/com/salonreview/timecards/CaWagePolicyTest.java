package com.salonreview.timecards;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonreview.timecards.TimecardTypes.BreakKind;
import com.salonreview.timecards.TimecardTypes.DayAnalysis;
import com.salonreview.timecards.TimecardTypes.DayInput;
import com.salonreview.timecards.TimecardTypes.DayPay;
import com.salonreview.timecards.TimecardTypes.DayTimecard;
import com.salonreview.timecards.TimecardTypes.Finding;
import com.salonreview.timecards.TimecardTypes.FindingCode;
import com.salonreview.timecards.TimecardTypes.TimeSpan;
import com.salonreview.timecards.TimecardTypes.TimecardBreak;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CaWagePolicyTest {

    private static final CaWageRules RULES = CaWageRules.defaults();
    private static final CaWagePolicy POLICY = new CaWagePolicy(RULES);
    private static final int RATE = 1850;

    private static java.time.Instant at(LocalDate d, String hhmm) {
        return LocalDateTime.of(d, LocalTime.parse(hhmm)).atZone(RULES.zone()).toInstant();
    }

    private static TimeSpan span(LocalDate d, String a, String b) {
        return new TimeSpan(at(d, a), at(d, b));
    }

    private static TimecardBreak unpaid(LocalDate d, String a, String b) {
        return new TimecardBreak(span(d, a, b), BreakKind.UNPAID);
    }

    private static TimecardBreak rest(LocalDate d, String a, String b) {
        return new TimecardBreak(span(d, a, b), BreakKind.PAID_REST);
    }

    private static DayInput day(LocalDate d, String a, String b, TimecardBreak... breaks) {
        return DayInput.of(d, List.of(new DayTimecard(span(d, a, b), List.of(breaks))));
    }

    private static boolean has(DayAnalysis an, FindingCode code) {
        return an.findings().stream().map(Finding::code).anyMatch(c -> c == code);
    }

    @Test
    @DisplayName("October golden: 119.25 h, 4.92 h overtime, gross 2251.60 at 18.50")
    void octoberTotals() {
        int[] minutes = {150, 270, 480, 180, 390, 270, 390, 570, 450, 375, 240, 180, 225, 210, 420, 360, 300, 360,
            80, 180, 390, 685};
        long total = 0;
        long ot = 0;
        long cents = 0;
        for (int m : minutes) {
            DayPay p = POLICY.dailyPay(LocalDate.of(2026, 10, 1), m);
            total += p.total();
            ot += p.overtimeMinutes();
            cents += CaWagePolicy.cents(p, RATE);
        }
        assertThat(total).isEqualTo(7155);
        assertThat(ot).isEqualTo(295);
        assertThat(cents).isBetween(225158L, 225162L);
    }

    @Test
    @DisplayName("10/8: 9.5 paid hours, meal missing, split shift, both rests evidenced")
    void oct8() {
        LocalDate d = LocalDate.of(2026, 10, 8);
        DayAnalysis an = POLICY.analyzeDay(day(d, "07:45", "20:15",
                unpaid(d, "14:15", "17:15"), rest(d, "09:45", "09:55"), rest(d, "12:30", "12:40")), RATE);
        assertThat(an.paidMinutes()).isEqualTo(570);
        assertThat(an.mealPremium()).isTrue();
        assertThat(an.splitShiftCandidate()).isTrue();
        assertThat(an.restsRequired()).isEqualTo(2);
        assertThat(has(an, FindingCode.REST_MISSING)).isFalse();
        assertThat(has(an, FindingCode.OVERTIME_DAILY)).isTrue();
    }

    @Test
    @DisplayName("10/30: 6.5 h continuous, meal missing")
    void oct30() {
        LocalDate d = LocalDate.of(2026, 10, 30);
        DayAnalysis an = POLICY.analyzeDay(day(d, "12:30", "19:00",
                rest(d, "15:15", "15:25"), rest(d, "18:30", "18:40")), RATE);
        assertThat(an.mealPremium()).isTrue();
        assertThat(has(an, FindingCode.MEAL_MISSING)).isTrue();
    }

    @Test
    @DisplayName("10/25: 6.0 h, premium without waiver, waived with waiver")
    void oct25() {
        LocalDate d = LocalDate.of(2026, 10, 25);
        DayInput base = day(d, "12:00", "18:00", rest(d, "15:00", "15:10"));
        assertThat(POLICY.analyzeDay(base, RATE).mealPremium()).isTrue();
        DayInput waived = new DayInput(d, base.timecards(), true, false, 0);
        DayAnalysis an = POLICY.analyzeDay(waived, RATE);
        assertThat(an.mealPremium()).isFalse();
        assertThat(has(an, FindingCode.MEAL_WAIVED)).isTrue();
    }

    @Test
    @DisplayName("waiver does not help a day over 6 hours")
    void waiverOver6h() {
        LocalDate d = LocalDate.of(2026, 10, 30);
        DayInput base = day(d, "12:30", "19:00", rest(d, "15:15", "15:25"), rest(d, "18:30", "18:40"));
        DayInput waived = new DayInput(d, base.timecards(), true, false, 0);
        assertThat(POLICY.analyzeDay(waived, RATE).mealPremium()).isTrue();
    }

    @Test
    @DisplayName("10/7: split shift premium is 12.88")
    void oct7SplitShift() {
        LocalDate d = LocalDate.of(2026, 10, 7);
        DayInput in = DayInput.of(d, List.of(new DayTimecard(span(d, "13:00", "21:30"),
                List.of(unpaid(d, "15:15", "17:15")))));
        DayAnalysis an = POLICY.analyzeDay(in, RATE);
        assertThat(an.paidMinutes()).isEqualTo(390);
        assertThat(an.splitShiftCandidate()).isTrue();
        assertThat(an.mealPremium()).isFalse();
        Finding f = an.findings().stream()
                .filter(x -> x.code() == FindingCode.SPLIT_SHIFT_PREMIUM_OWED).findFirst().orElseThrow();
        assertThat(f.minutes()).isEqualTo(1288);
    }

    @Test
    @DisplayName("10/11: meal inside 5h is compliant")
    void oct11() {
        LocalDate d = LocalDate.of(2026, 10, 11);
        DayAnalysis an = POLICY.analyzeDay(day(d, "09:00", "15:45", unpaid(d, "13:15", "13:45"),
                rest(d, "11:00", "11:10")), RATE);
        assertThat(an.paidMinutes()).isEqualTo(375);
        assertThat(an.mealPremium()).isFalse();
    }

    @Test
    @DisplayName("10/26: 80 minutes goes to reporting time review")
    void oct26() {
        LocalDate d = LocalDate.of(2026, 10, 26);
        DayAnalysis an = POLICY.analyzeDay(day(d, "10:00", "11:20"), RATE);
        assertThat(has(an, FindingCode.REPORTING_TIME_REVIEW)).isTrue();
    }

    @Test
    @DisplayName("10/31: three breaks, no meal premium, 205 min overtime, rests not evidenced")
    void oct31() {
        LocalDate d = LocalDate.of(2026, 10, 31);
        DayAnalysis an = POLICY.analyzeDay(day(d, "07:45", "20:45", unpaid(d, "11:15", "12:00"),
                unpaid(d, "14:00", "14:20"), unpaid(d, "17:00", "17:30")), RATE);
        assertThat(an.paidMinutes()).isEqualTo(685);
        assertThat(an.mealPremium()).isFalse();
        assertThat(an.restsRequired()).isEqualTo(3);
        assertThat(has(an, FindingCode.REST_MISSING)).isTrue();
        assertThat(has(an, FindingCode.REST_PREMIUM_OWED)).isFalse();
    }

    @Test
    @DisplayName("rest premium only when the rest was not provided")
    void restPremium() {
        LocalDate d = LocalDate.of(2026, 10, 3);
        DayInput in = day(d, "09:00", "14:00", unpaid(d, "11:00", "11:30"));
        DayInput denied = new DayInput(d, in.timecards(), false, true, 0);
        assertThat(POLICY.analyzeDay(denied, RATE).restPremium()).isTrue();
        assertThat(POLICY.analyzeDay(in, RATE).restPremium()).isFalse();
    }

    @Test
    @DisplayName("rest tiers follow 3.5h / 6h / 10h / 14h")
    void restTiers() {
        assertThat(RULES.restsRequired(210)).isZero();
        assertThat(RULES.restsRequired(211)).isEqualTo(1);
        assertThat(RULES.restsRequired(360)).isEqualTo(1);
        assertThat(RULES.restsRequired(361)).isEqualTo(2);
        assertThat(RULES.restsRequired(600)).isEqualTo(2);
        assertThat(RULES.restsRequired(601)).isEqualTo(3);
        assertThat(RULES.restsRequired(841)).isEqualTo(4);
    }

    @Test
    @DisplayName("daily double time over 12 hours")
    void doubleTime() {
        DayPay p = POLICY.dailyPay(LocalDate.of(2026, 10, 1), 13 * 60);
        assertThat(p.regularMinutes()).isEqualTo(480);
        assertThat(p.overtimeMinutes()).isEqualTo(240);
        assertThat(p.doubleTimeMinutes()).isEqualTo(60);
    }

    @Test
    @DisplayName("weekly overtime after 40 regular hours")
    void weekly() {
        LocalDate mon = LocalDate.of(2026, 10, 5);
        List<LocalDate> dates = new ArrayList<>();
        List<Long> mins = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            dates.add(mon.plusDays(i));
            mins.add(i < 5 ? 540L : 120L);
        }
        List<DayPay> out = POLICY.weekPay(dates, mins);
        assertThat(out.get(4).regularMinutes()).isEqualTo(480);
        assertThat(out.get(5).regularMinutes()).isZero();
        assertThat(out.get(5).overtimeMinutes()).isEqualTo(120);
    }

    @Test
    @DisplayName("seventh consecutive day is all overtime")
    void seventhDay() {
        LocalDate mon = LocalDate.of(2026, 10, 5);
        List<LocalDate> dates = new ArrayList<>();
        List<Long> mins = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            dates.add(mon.plusDays(i));
            mins.add(240L);
        }
        DayPay last = POLICY.weekPay(dates, mins).get(6);
        assertThat(last.regularMinutes()).isZero();
        assertThat(last.overtimeMinutes()).isEqualTo(240);
    }
}
