package com.salonreview.metrics;

import com.salonreview.metrics.ResponseTimes.Attribution;
import com.salonreview.metrics.ResponseTimes.Msg;
import com.salonreview.metrics.ResponseTimes.Shift;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseTimesTest {

    private static final Instant T0 = Instant.parse("2026-11-10T17:00:00Z");
    private static final Instant NOW = T0.plusSeconds(24 * 3600);
    private static long ids = 0;

    private static Instant at(long minutes) { return T0.plusSeconds(minutes * 60); }
    private static Msg in(String phone, long min) { return new Msg(++ids, phone, true, at(min), false, null, false); }
    private static Msg ignoredIn(String phone, long min) { return new Msg(++ids, phone, true, at(min), false, null, true); }
    private static Msg reply(String phone, long min, Long sender) { return new Msg(++ids, phone, false, at(min), true, sender, false); }
    private static Msg auto(String phone, long min) { return new Msg(++ids, phone, false, at(min), false, null, false); }
    private static Shift shift(long user, long fromMin, long toMin) { return new Shift(user, at(fromMin), at(toMin)); }

    @Test
    @DisplayName("simple reply during a shift is measured in wall seconds and attributed directly")
    void simpleReply() {
        var r = ResponseTimes.compute(List.of(in("a", 0), reply("a", 4, 1L)), List.of(shift(1, -60, 600)), NOW);
        assertThat(r.replies()).hasSize(1);
        var x = r.replies().get(0);
        assertThat(x.effectiveSeconds()).isEqualTo(240);
        assertThat(x.attribution()).isEqualTo(Attribution.DIRECT);
        assertThat(x.userId()).isEqualTo(1L);
        assertThat(x.onShift()).isTrue();
    }

    @Test
    @DisplayName("consecutive inbound texts are one wait, measured from the first")
    void consecutiveInbound() {
        var r = ResponseTimes.compute(List.of(in("a", 0), in("a", 3), reply("a", 10, 1L)), List.of(shift(1, -60, 600)), NOW);
        assertThat(r.replies()).hasSize(1);
        assertThat(r.replies().get(0).effectiveSeconds()).isEqualTo(600);
    }

    @Test
    @DisplayName("overnight wait only counts time while someone is clocked in")
    void nightExcluded() {
        // inbound at minute 0, shift ends at 10, next shift starts at 600; reply at 605
        var shifts = List.of(shift(1, -60, 10), shift(1, 600, 700));
        var r = ResponseTimes.compute(List.of(in("a", 0), reply("a", 605, 1L)), shifts, NOW);
        assertThat(r.replies().get(0).effectiveSeconds()).isEqualTo((10 + 5) * 60);
    }

    @Test
    @DisplayName("automation sends never answer a client and ignorable inbound never starts a wait")
    void automationAndKeywords() {
        var r = ResponseTimes.compute(List.of(in("a", 0), auto("a", 1), ignoredIn("b", 0), reply("b", 5, 1L)),
                List.of(shift(1, -60, 600)), NOW);
        assertThat(r.replies()).isEmpty();
        assertThat(r.unanswered()).hasSize(1);
        assertThat(r.unanswered().get(0).phone()).isEqualTo("a");
    }

    @Test
    @DisplayName("a reply with no known sender and exactly one manager on shift is inferred")
    void inferredAttribution() {
        var r = ResponseTimes.compute(List.of(in("a", 0), reply("a", 2, null)), List.of(shift(7, -10, 60)), NOW);
        assertThat(r.replies().get(0).attribution()).isEqualTo(Attribution.INFERRED);
        assertThat(r.replies().get(0).userId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("an unattributed reply with two managers on shift is unknown")
    void ambiguousIsUnknown() {
        var r = ResponseTimes.compute(List.of(in("a", 0), reply("a", 2, null)),
                List.of(shift(7, -10, 60), shift(8, -10, 60)), NOW);
        assertThat(r.replies().get(0).attribution()).isEqualTo(Attribution.UNKNOWN);
        assertThat(r.replies().get(0).userId()).isNull();
    }

    @Test
    @DisplayName("a reply sent while nobody is clocked in is off shift")
    void offShift() {
        var r = ResponseTimes.compute(List.of(in("a", 0), reply("a", 300, 1L)), List.of(shift(1, -60, 10)), NOW);
        assertThat(r.replies().get(0).onShift()).isFalse();
    }

    @Test
    @DisplayName("open shifts run to now and shape the unanswered wait")
    void openShiftAndUnanswered() {
        Instant now = at(30);
        var r = ResponseTimes.compute(List.of(in("a", 10)), List.of(new Shift(1, at(0), null)), now);
        assertThat(r.unanswered()).hasSize(1);
        assertThat(r.unanswered().get(0).effectiveSeconds()).isEqualTo(20 * 60);
    }

    @Test
    @DisplayName("overlapping shifts of two managers are not double counted")
    void unionDoesNotDoubleCount() {
        var r = ResponseTimes.compute(List.of(in("a", 0), reply("a", 10, 1L)),
                List.of(shift(1, -5, 20), shift(2, -5, 20)), NOW);
        assertThat(r.replies().get(0).effectiveSeconds()).isEqualTo(600);
    }

    @Test
    @DisplayName("shiftSeconds sums one manager's overlap with the window")
    void shiftSeconds() {
        var shifts = List.of(shift(1, 0, 60), shift(1, 30, 90), shift(2, 0, 600));
        assertThat(ResponseTimes.shiftSeconds(shifts, 1, at(0), at(120), NOW)).isEqualTo(90 * 60);
        assertThat(ResponseTimes.shiftSeconds(shifts, 1, at(60), at(120), NOW)).isEqualTo(30 * 60);
    }

    @Test
    @DisplayName("stats: median, average and shares within 5 and 15 minutes")
    void stats() {
        var s = ResponseTimes.stats(List.of(60L, 120L, 600L, 3600L));
        assertThat(s.replies()).isEqualTo(4);
        assertThat(s.medianSeconds()).isEqualTo(360);
        assertThat(s.averageSeconds()).isEqualTo(1095);
        assertThat(s.within5Percent()).isEqualTo(50);
        assertThat(s.within15Percent()).isEqualTo(75);
        assertThat(ResponseTimes.stats(List.of()).medianSeconds()).isNull();
    }
}
