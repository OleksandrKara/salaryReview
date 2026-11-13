package com.salonreview.timecards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.salonreview.timecards.TimecardDayService.DayCalc;
import com.salonreview.timecards.TimecardDtos.AnswerDto;
import com.salonreview.timecards.TimecardDtos.BreakQuestionDto;
import com.salonreview.timecards.TimecardTypes.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TimecardBreakQuestionsTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 6);
    private static final Instant START = Instant.parse("2026-11-06T17:00:00Z");
    private static final CaWagePolicy POLICY = new CaWagePolicy(TimecardDayService.RULES);

    private static DayTimecard card(long minutes, TimecardBreak... breaks) {
        return new DayTimecard(new TimeSpan(START, START.plusSeconds(minutes * 60)), List.of(breaks));
    }

    private static TimecardBreak meal(long fromMin, long len) {
        return new TimecardBreak(new TimeSpan(START.plusSeconds(fromMin * 60), START.plusSeconds((fromMin + len) * 60)), BreakKind.UNPAID);
    }

    private static TimecardBreak rest(long fromMin) {
        return new TimecardBreak(new TimeSpan(START.plusSeconds(fromMin * 60), START.plusSeconds((fromMin + 10) * 60)), BreakKind.PAID_REST);
    }

    private static List<BreakQuestionDto> ask(List<DayTimecard> cards, boolean generated, boolean waiver) {
        DayAnalysis a = POLICY.analyzeDay(new DayInput(DAY, generated ? TimecardDayService.stripRests(cards) : cards,
                waiver, false, 0, null), 1850);
        return TimecardDayService.breakQuestions(cards, a, a.paidMinutes(), generated, waiver);
    }

    private static long count(List<BreakQuestionDto> qs, String kind) {
        return qs.stream().filter(q -> q.kind().equals(kind)).count();
    }

    @Test
    void shortDayAsksNothing() {
        assertTrue(ask(List.of(card(180)), false, false).isEmpty());
        assertTrue(ask(List.of(card(210)), false, false).isEmpty());
        assertTrue(ask(List.of(card(210, meal(100, 30))), true, false).isEmpty());
    }

    @Test
    void justOverRestThresholdAsksOnlyTheRest() {
        List<BreakQuestionDto> qs = ask(List.of(card(240)), false, false);
        assertEquals(1, count(qs, "REST"));
        assertEquals(0, count(qs, "MEAL"));
        assertEquals(1, count(ask(List.of(card(300)), false, false), "REST"));
        assertEquals(0, count(ask(List.of(card(300)), false, false), "MEAL"));
    }

    @Test
    void mealThresholdEdges() {
        assertEquals(0, count(ask(List.of(card(300)), false, false), "MEAL"));
        assertEquals(1, count(ask(List.of(card(331)), false, false), "MEAL"));
        assertEquals(0, count(ask(List.of(card(360)), false, true), "MEAL"));
        assertEquals(1, count(ask(List.of(card(600 + 30, meal(200, 30))), true, false), "MEAL"));
        assertEquals(2, count(ask(List.of(card(640)), false, false), "MEAL"));
    }

    @Test
    void secondRestOverSixHours() {
        assertEquals(1, count(ask(List.of(card(390, meal(180, 30))), true, false), "REST"));
        assertEquals(2, count(ask(List.of(card(420, meal(180, 30))), true, false), "REST"));
    }

    @Test
    void evidenceThatSatisfiesTheRulesAsksNothing() {
        List<DayTimecard> cards = List.of(card(450, meal(180, 30), rest(80), rest(300)));
        assertTrue(ask(cards, false, false).isEmpty());
    }

    @Test
    void onlyTheMissingKindIsAskedWhenEvidenceIsPartial() {
        List<BreakQuestionDto> qs = ask(List.of(card(450, meal(180, 30))), false, false);
        assertEquals(0, count(qs, "MEAL"));
        assertEquals(2, count(qs, "REST"));
    }

    @Test
    void proposedBreaksAreAssumptionsAndStillAskedForRequiredKindsOnly() {
        List<BreakQuestionDto> qs = ask(List.of(card(450, meal(180, 30), rest(80), rest(300))), true, false);
        assertEquals(1, count(qs, "MEAL"));
        assertEquals(2, count(qs, "REST"));
        assertTrue(qs.stream().allMatch(q -> q.assumedStart() != null));
        assertTrue(ask(List.of(card(200, rest(60))), true, false).isEmpty());
    }

    @Test
    void shortDayProducesNoAttentionAndUnrequiredBreaksNeverFlag() {
        List<DayTimecard> cards = List.of(card(180));
        DayAnalysis a = POLICY.analyzeDay(new DayInput(DAY, cards, false, false, 0, null), 1850);
        DayCalc c = new DayCalc(DAY, 43L, "SQUARE", cards, List.of(), a, 1850,
                new TimecardDayService.ReviewRow(1, "EXTERNAL", "WORKER_CONFIRMED", null, false, false, false, null,
                        1, null, null, "[]"), "WORKER_CONFIRMED", false, false, false);
        assertTrue(new TimecardDayService(null, false).attentionReasons(c, a, List.of()).isEmpty());
    }

    @Test
    void missedRequiredRestAndNoAnswerAreFlagged() {
        List<DayTimecard> cards = List.of(card(450, meal(180, 30)));
        DayAnalysis a = POLICY.analyzeDay(new DayInput(DAY, cards, false, true, 0, null), 1850);
        DayCalc c = new DayCalc(DAY, 43L, "SQUARE", cards, List.of(), a, 1850,
                new TimecardDayService.ReviewRow(1, "EXTERNAL", "WORKER_CONFIRMED", null, false, false, true, null,
                        1, null, null, "[]"), "WORKER_CONFIRMED", false, false, false);
        List<String> reasons = new TimecardDayService(null, false).attentionReasons(c, a,
                List.of(new AnswerDto("REST", 0, "NO", null, null, "busy")));
        assertTrue(reasons.contains("REST_MISSING") || reasons.contains("REST_PREMIUM_OWED"));
        assertTrue(reasons.contains("WORKER_NO_REST"));
        assertFalse(reasons.contains("WORKER_NO_MEAL"));
    }

    @Test
    void patternLevels() {
        assertEquals("NONE", TimecardController.patternLevel(2));
        assertEquals("YELLOW", TimecardController.patternLevel(3));
        assertEquals("YELLOW", TimecardController.patternLevel(4));
        assertEquals("RED", TimecardController.patternLevel(5));
    }

    private static final TimecardReviewService SVC = new TimecardReviewService(null, null, null, null, null);
    private static final BreakQuestionDto MEAL_Q = new BreakQuestionDto("MEAL", 0, START.toString(), START.plusSeconds(1800).toString());

    @Test
    void noAnswerNeedsAReason() {
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> SVC.resolveAnswer(MEAL_Q, new AnswerDto("MEAL", 0, "NO", null, null, " "), new ArrayList<>()));
        List<TimecardBreak> out = new ArrayList<>();
        AnswerDto ok = SVC.resolveAnswer(MEAL_Q, new AnswerDto("MEAL", 0, "NO", null, null, "busy"), out);
        assertEquals("busy", ok.reason());
        assertTrue(out.isEmpty());
    }

    @Test
    void yesConfirmsAssumedBreakAndShortMealNeedsReason() {
        List<TimecardBreak> out = new ArrayList<>();
        SVC.resolveAnswer(MEAL_Q, new AnswerDto("MEAL", 0, "YES", null, null, null), out);
        assertEquals(1, out.size());
        String s = START.plusSeconds(3600).toString();
        String e = START.plusSeconds(3600 + 20 * 60).toString();
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> SVC.resolveAnswer(MEAL_Q, new AnswerDto("MEAL", 0, "DIFF", s, e, null), new ArrayList<>()));
        SVC.resolveAnswer(MEAL_Q, new AnswerDto("MEAL", 0, "DIFF", s, e, "client ran late"), out);
        assertEquals(2, out.size());
    }
}
